//! usbip-share: export USB devices plugged into this Mac to the Docker
//! Desktop VM over USB/IP.
//!
//! Docker Desktop cannot pass USB devices to containers, but its Linux kernel
//! ships the USB/IP virtual host controller (vhci_hcd) built in. What macOS
//! lacks is a USB/IP *server*. This is that server: it watches the USB bus
//! through libusb, exports Android devices (chosen by vendor id) and proxies
//! their USB transfers to the `usbip` sidecar in docker-compose.mac-usb.yml,
//! which imports them into the VM. From there they are ordinary USB devices
//! for any privileged container that mounts /dev/bus/usb.
//!
//! The proxy mirrors what the Linux usbip-host driver does: descriptors and
//! control requests pass straight through to the device, except the handful
//! of requests that have to go through the USB stack's own API
//! (SET_CONFIGURATION, SET_INTERFACE, CLEAR_FEATURE/halt). Every URB runs on
//! its own blocking thread, so a pending read on one endpoint never blocks a
//! write on another; adb and fastboot depend on that. Reads stay pending
//! until the device answers or the client unlinks them.
//!
//! Hotplug: a phone rebooting into fastboot or EDL re-enumerates as a new USB
//! device. The rescan loop exports new devices as they appear and, when one
//! disappears, drops the connection that imported it, so the VM sees a clean
//! unplug and the sidecar attaches the replacement.

use clap::Parser;
use log::{debug, error, info, warn};
use rusb::{Device, DeviceDescriptor, DeviceHandle, GlobalContext, TransferType};
use std::collections::{HashMap, HashSet};
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Notify};

/// Vendor ids exported by default: Android phones in adb, fastboot, download
/// and EDL modes enumerate under one of these.
const ANDROID_VENDORS: &[(u16, &str)] = &[
    (0x18d1, "Google"),
    (0x04e8, "Samsung"),
    (0x05c6, "Qualcomm"),
    (0x0e8d, "MediaTek"),
    (0x1782, "Spreadtrum/Unisoc"),
    (0x2a70, "OnePlus"),
    (0x22b8, "Motorola"),
    (0x2717, "Xiaomi"),
    (0x12d1, "Huawei"),
    (0x0bb4, "HTC"),
    (0x1004, "LG"),
    (0x0fce, "Sony"),
    (0x2ae5, "Fairphone"),
    (0x1f3a, "Allwinner"),
    (0x2207, "Rockchip"),
    (0x19d2, "ZTE"),
    (0x0b05, "ASUS"),
    (0x2a45, "Meizu"),
    (0x1bbb, "TCL/Alcatel"),
    (0x0489, "Foxconn"),
    (0x1ebf, "Coolpad"),
    (0x2b4c, "Zuk"),
    (0x0502, "Acer"),
    (0x0e79, "Archos"),
    (0x413c, "Dell"),
    (0x1d4d, "Pegatron"),
    (0x2916, "Yota"),
    (0x8087, "Intel"),
    (0x0955, "NVIDIA"),
    (0x1949, "Amazon"),
    (0x2d95, "vivo"),
    (0x22d9, "OPPO/realme"),
    (0x17ef, "Lenovo"),
    (0x0421, "Nokia"),
    (0x2e04, "HMD"),
    (0x04dd, "Sharp"),
    (0x0482, "Kyocera"),
];

// ---------------------------------------------------------------------------
// USB/IP wire protocol (all big-endian)
// ---------------------------------------------------------------------------

const USBIP_VERSION: u16 = 0x0111;
const OP_REQ_DEVLIST: u16 = 0x8005;
const OP_REP_DEVLIST: u16 = 0x0005;
const OP_REQ_IMPORT: u16 = 0x8003;
const OP_REP_IMPORT: u16 = 0x0003;
const ST_OK: u32 = 0;
const ST_NA: u32 = 1;
const USBIP_CMD_SUBMIT: u32 = 1;
const USBIP_CMD_UNLINK: u32 = 2;
const USBIP_RET_SUBMIT: u32 = 3;
const USBIP_RET_UNLINK: u32 = 4;
const USBIP_DIR_IN: u32 = 1;

// Linux errno values the vhci driver understands in URB status fields.
const ENOENT: i32 = 2;
const EIO: i32 = 5;
const ENODEV: i32 = 19;
const EINVAL: i32 = 22;
const EPIPE: i32 = 32;
const EPROTO: i32 = 71;
const EOVERFLOW: i32 = 75;
const ECONNRESET: i32 = 104;
const ETIMEDOUT: i32 = 110;

/// How long a pending IN transfer waits between checks for an unlink.
const IN_POLL: Duration = Duration::from_millis(50);
/// OUT transfers: the device may NAK for a long time while it writes flash.
const OUT_TIMEOUT: Duration = Duration::from_secs(120);
const CONTROL_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Parser, Debug)]
#[command(
    name = "usbip-share",
    about = "Export USB devices (Android phones by default) to the Docker Desktop VM over USB/IP"
)]
struct Args {
    /// Listen address. Docker Desktop delivers container connections to
    /// host.docker.internal on the Mac's loopback interface, so the default
    /// is reachable from containers without being exposed to the network.
    #[arg(long, default_value = "127.0.0.1:3240")]
    bind: SocketAddr,
    /// Extra vendor id to export (hex, e.g. 0x1234). Repeatable.
    #[arg(long = "vid", value_name = "HEX", value_parser = parse_hex_u16)]
    vids: Vec<u16>,
    /// Export every USB device, not only known Android vendors (hubs are always skipped).
    #[arg(long)]
    all: bool,
    /// Bus rescan interval in milliseconds. Keep it short: a MediaTek
    /// preloader only answers for a second or two after power-on.
    #[arg(long, default_value_t = 250)]
    poll_ms: u64,
    /// Never export this device: a serial number, a vid:pid pair (hex) or a
    /// bus id. Repeatable. Useful to leave one phone to the Mac.
    #[arg(long = "exclude", value_name = "SERIAL|VID:PID|BUSID")]
    excludes: Vec<String>,
    /// File with one exclusion per line, re-read every scan, so devices can
    /// be released to the Mac (append a line) or reclaimed (remove it) while
    /// the server runs. `make usb-release DEV=<serial>` edits it.
    #[arg(long, default_value = "~/.usbip-share/exclude")]
    exclude_file: String,
    /// List the vendor ids exported by default and exit.
    #[arg(long)]
    list_vendors: bool,
}

fn expand_home(p: &str) -> std::path::PathBuf {
    if let Some(rest) = p.strip_prefix("~/") {
        if let Ok(home) = std::env::var("HOME") {
            return std::path::Path::new(&home).join(rest);
        }
    }
    std::path::PathBuf::from(p)
}

/// Exclusions from the command line plus the exclude file (lower-cased).
struct Excludes {
    fixed: Vec<String>,
    file: std::path::PathBuf,
}

impl Excludes {
    fn current(&self) -> HashSet<String> {
        let mut set: HashSet<String> = self.fixed.iter().map(|s| s.to_lowercase()).collect();
        if let Ok(text) = std::fs::read_to_string(&self.file) {
            for line in text.lines() {
                let l = line.split('#').next().unwrap_or("").trim().to_lowercase();
                if !l.is_empty() {
                    set.insert(l);
                }
            }
        }
        set
    }

    fn matches(set: &HashSet<String>, key: &DevKey, serial: Option<&str>, busid: &str) -> bool {
        if set.is_empty() {
            return false;
        }
        set.contains(&format!("{:04x}:{:04x}", key.vid, key.pid))
            || set.contains(&busid.to_lowercase())
            || serial
                .map(|s| set.contains(&s.trim().to_lowercase()))
                .unwrap_or(false)
    }
}

fn parse_hex_u16(s: &str) -> Result<u16, String> {
    let t = s.trim().trim_start_matches("0x").trim_start_matches("0X");
    u16::from_str_radix(t, 16).map_err(|e| format!("{s:?} is not a hex vendor id: {e}"))
}

fn be32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

fn put_fixed(out: &mut Vec<u8>, s: &str, len: usize) {
    let b = s.as_bytes();
    let n = b.len().min(len - 1);
    out.extend_from_slice(&b[..n]);
    out.extend(std::iter::repeat(0u8).take(len - n));
}

fn cstr(b: &[u8]) -> String {
    let end = b.iter().position(|&c| c == 0).unwrap_or(b.len());
    String::from_utf8_lossy(&b[..end]).into_owned()
}

// ---------------------------------------------------------------------------
// Exported devices
// ---------------------------------------------------------------------------

/// What USB/IP tells the client about a device (struct usbip_usb_device).
#[derive(Clone)]
struct DeviceInfo {
    path: String,
    busid: String,
    busnum: u32,
    devnum: u32,
    speed: u32,
    id_vendor: u16,
    id_product: u16,
    bcd_device: u16,
    class: u8,
    subclass: u8,
    protocol: u8,
    config_value: u8,
    num_configs: u8,
    /// (class, subclass, protocol) of each interface's first alt setting
    interfaces: Vec<(u8, u8, u8)>,
}

impl DeviceInfo {
    /// The 312-byte usbip_usb_device block.
    fn write(&self, out: &mut Vec<u8>) {
        put_fixed(out, &self.path, 256);
        put_fixed(out, &self.busid, 32);
        out.extend(self.busnum.to_be_bytes());
        out.extend(self.devnum.to_be_bytes());
        out.extend(self.speed.to_be_bytes());
        out.extend(self.id_vendor.to_be_bytes());
        out.extend(self.id_product.to_be_bytes());
        out.extend(self.bcd_device.to_be_bytes());
        out.extend([
            self.class,
            self.subclass,
            self.protocol,
            self.config_value,
            self.num_configs,
            self.interfaces.len() as u8,
        ]);
    }
}

/// Tells a connection to stop; the socket closes and the VM sees an unplug.
#[derive(Default)]
struct Cancel {
    flag: AtomicBool,
    notify: Notify,
}

impl Cancel {
    fn cancel(&self) {
        self.flag.store(true, Ordering::SeqCst);
        self.notify.notify_waiters();
        self.notify.notify_one();
    }
}

struct Exported {
    info: DeviceInfo,
    label: String,
    handle: Arc<DeviceHandle<GlobalContext>>,
    /// transfer type per endpoint address (direction bit included)
    ep_types: HashMap<u8, TransferType>,
    in_use: AtomicBool,
    conn: Mutex<Option<Arc<Cancel>>>,
    /// interfaces another macOS process held when we exported the device
    unclaimed: Mutex<Vec<u8>>,
    last_claim_retry: Mutex<Instant>,
}

impl Exported {
    /// Retry claims that failed earlier (e.g. the Mac's adb server let go).
    fn retry_claims(&self) {
        let mut pending = self.unclaimed.lock().unwrap();
        if pending.is_empty() {
            return;
        }
        let mut last = self.last_claim_retry.lock().unwrap();
        if last.elapsed() < Duration::from_secs(2) {
            return;
        }
        *last = Instant::now();
        pending.retain(|&n| match self.handle.claim_interface(n) {
            Ok(()) => {
                info!("{}: interface {n} is ours now (the other process released it)", self.label);
                false
            }
            Err(_) => true,
        });
    }
}

#[derive(Default)]
struct Registry {
    devices: Mutex<HashMap<String, Arc<Exported>>>,
}

impl Registry {
    fn devlist_reply(&self) -> Vec<u8> {
        let devices = self.devices.lock().unwrap();
        let free: Vec<&Arc<Exported>> = devices
            .values()
            .filter(|d| !d.in_use.load(Ordering::SeqCst))
            .collect();
        let mut out = Vec::new();
        out.extend(USBIP_VERSION.to_be_bytes());
        out.extend(OP_REP_DEVLIST.to_be_bytes());
        out.extend(ST_OK.to_be_bytes());
        out.extend((free.len() as u32).to_be_bytes());
        for d in free {
            d.info.write(&mut out);
            for (c, s, p) in &d.info.interfaces {
                out.extend([*c, *s, *p, 0]);
            }
        }
        out
    }

    /// Reserve a device for one importing connection.
    fn take(&self, busid: &str, cancel: Arc<Cancel>) -> Option<Arc<Exported>> {
        let devices = self.devices.lock().unwrap();
        let d = devices.get(busid)?;
        if d
            .in_use
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return None;
        }
        *d.conn.lock().unwrap() = Some(cancel);
        Some(d.clone())
    }

    fn release(&self, d: &Exported) {
        *d.conn.lock().unwrap() = None;
        d.in_use.store(false, Ordering::SeqCst);
    }

    fn remove(&self, busid: &str) -> Option<Arc<Exported>> {
        self.devices.lock().unwrap().remove(busid)
    }

    fn insert(&self, d: Arc<Exported>) {
        self.devices
            .lock()
            .unwrap()
            .insert(d.info.busid.clone(), d);
    }
}

fn speed_code(s: rusb::Speed) -> u32 {
    match s {
        rusb::Speed::Low => 1,
        rusb::Speed::Full => 2,
        rusb::Speed::High => 3,
        rusb::Speed::Super => 5,
        rusb::Speed::SuperPlus => 6,
        _ => 0,
    }
}

fn export(
    dev: &Device<GlobalContext>,
    desc: &DeviceDescriptor,
    handle: DeviceHandle<GlobalContext>,
    serial: Option<&str>,
) -> Result<Exported, String> {
    let product = handle.read_product_string_ascii(desc).unwrap_or_default();
    let manufacturer = handle
        .read_manufacturer_string_ascii(desc)
        .unwrap_or_default();
    let mut label = format!(
        "{:04x}:{:04x} {} {}",
        desc.vendor_id(),
        desc.product_id(),
        manufacturer.trim(),
        product.trim()
    )
    .trim()
    .to_string();
    if let Some(sn) = serial {
        label.push_str(&format!(" [{}]", sn.trim()));
    }

    let cfg = dev
        .active_config_descriptor()
        .map_err(|e| format!("no active configuration: {e}"))?;

    let mut interfaces = Vec::new();
    let mut ep_types = HashMap::new();
    let mut unclaimed = Vec::new();
    for intf in cfg.interfaces() {
        // Claim every interface up front: macOS only lets libusb move bulk and
        // interrupt data on claimed interfaces. A claim fails when another
        // macOS process already owns the interface, typically a host-side adb
        // server.
        if let Err(e) = handle.claim_interface(intf.number()) {
            warn!(
                "{label}: interface {} is busy ({e}); another macOS process holds it. \
                 If that is adb, run `adb kill-server` on the Mac. Retrying every scan.",
                intf.number()
            );
            unclaimed.push(intf.number());
        }
        for (i, alt) in intf.descriptors().enumerate() {
            if i == 0 {
                interfaces.push((alt.class_code(), alt.sub_class_code(), alt.protocol_code()));
            }
            for ep in alt.endpoint_descriptors() {
                ep_types.entry(ep.address()).or_insert(ep.transfer_type());
            }
        }
    }

    let busnum = dev.bus_number() as u32;
    let devnum = dev.address() as u32;
    let busid = format!("{}-{}-{}", dev.bus_number(), dev.address(), dev.port_number());
    let info = DeviceInfo {
        path: format!("/sys/devices/usb/{busid}"),
        busid,
        busnum,
        devnum,
        speed: speed_code(dev.speed()),
        id_vendor: desc.vendor_id(),
        id_product: desc.product_id(),
        bcd_device: {
            let v = desc.device_version();
            ((v.major() as u16) << 8) | ((v.minor() as u16) << 4) | v.sub_minor() as u16
        },
        class: desc.class_code(),
        subclass: desc.sub_class_code(),
        protocol: desc.protocol_code(),
        config_value: cfg.number(),
        num_configs: desc.num_configurations(),
        interfaces,
    };
    Ok(Exported {
        info,
        label,
        handle: Arc::new(handle),
        ep_types,
        in_use: AtomicBool::new(false),
        conn: Mutex::new(None),
        unclaimed: Mutex::new(unclaimed),
        last_claim_retry: Mutex::new(Instant::now()),
    })
}

// ---------------------------------------------------------------------------
// URB execution (on blocking threads)
// ---------------------------------------------------------------------------

struct UrbResult {
    status: i32,
    actual_length: i32,
    data: Vec<u8>,
}

impl UrbResult {
    fn fail(status: i32) -> Self {
        UrbResult { status, actual_length: 0, data: Vec::new() }
    }
    fn ok(actual_length: usize, data: Vec<u8>) -> Self {
        UrbResult { status: 0, actual_length: actual_length as i32, data }
    }
}

fn errno(e: rusb::Error) -> i32 {
    match e {
        rusb::Error::Pipe => -EPIPE,
        rusb::Error::NoDevice => -ENODEV,
        rusb::Error::Timeout => -ETIMEDOUT,
        rusb::Error::Overflow => -EOVERFLOW,
        rusb::Error::NotFound => -ENOENT,
        rusb::Error::InvalidParam => -EINVAL,
        rusb::Error::Io => -EIO,
        _ => -EPROTO,
    }
}

fn perform_control(dev: &Exported, is_in: bool, setup: &[u8; 8], data: &[u8]) -> UrbResult {
    let h = &dev.handle;
    let bm_request_type = setup[0];
    let b_request = setup[1];
    let w_value = u16::from_le_bytes([setup[2], setup[3]]);
    let w_index = u16::from_le_bytes([setup[4], setup[5]]);
    let w_length = u16::from_le_bytes([setup[6], setup[7]]);
    let recipient = bm_request_type & 0x1f;
    let standard = (bm_request_type >> 5) & 0x3 == 0;

    // Requests the host stack must perform through its own API rather than as
    // raw control transfers (the same list the Linux usbip-host driver tweaks).
    if standard {
        match (b_request, recipient) {
            (9, 0) => {
                // SET_CONFIGURATION: the device is already configured on the
                // Mac side; only act if the client wants a different one.
                let want = (w_value & 0xff) as u8;
                let current = h.active_configuration().unwrap_or(0);
                debug!("{}: SET_CONFIGURATION {want} (current {current})", dev.label);
                if current == want {
                    return UrbResult::ok(0, Vec::new());
                }
                return match h.set_active_configuration(want) {
                    Ok(()) => UrbResult::ok(0, Vec::new()),
                    Err(e) => UrbResult::fail(errno(e)),
                };
            }
            (11, 1) => {
                debug!("{}: SET_INTERFACE {} alt {}", dev.label, w_index, w_value);
                return match h.set_alternate_setting(w_index as u8, w_value as u8) {
                    Ok(()) => UrbResult::ok(0, Vec::new()),
                    Err(e) => UrbResult::fail(errno(e)),
                };
            }
            (1, 2) if w_value == 0 => {
                debug!("{}: CLEAR_FEATURE halt ep {:#04x}", dev.label, w_index);
                return match h.clear_halt(w_index as u8) {
                    Ok(()) => UrbResult::ok(0, Vec::new()),
                    Err(e) => UrbResult::fail(errno(e)),
                };
            }
            (5, 0) => return UrbResult::ok(0, Vec::new()), // SET_ADDRESS is vhci's business
            _ => {}
        }
    }

    if is_in {
        let mut buf = vec![0u8; w_length as usize];
        match h.read_control(bm_request_type, b_request, w_value, w_index, &mut buf, CONTROL_TIMEOUT) {
            Ok(n) => {
                buf.truncate(n);
                UrbResult::ok(n, buf)
            }
            Err(e) => UrbResult::fail(errno(e)),
        }
    } else {
        match h.write_control(bm_request_type, b_request, w_value, w_index, data, CONTROL_TIMEOUT) {
            Ok(n) => UrbResult::ok(n, Vec::new()),
            Err(e) => UrbResult::fail(errno(e)),
        }
    }
}

fn perform_urb(
    dev: &Exported,
    is_in: bool,
    ep: u8,
    length: usize,
    setup: &[u8; 8],
    data: &[u8],
    iso: bool,
    unlinked: &AtomicBool,
) -> UrbResult {
    if iso {
        return UrbResult::fail(-EINVAL);
    }
    if ep == 0 {
        return perform_control(dev, is_in, setup, data);
    }
    let h = &dev.handle;
    let addr = ep | if is_in { 0x80 } else { 0 };
    let ttype = dev
        .ep_types
        .get(&addr)
        .copied()
        .unwrap_or(TransferType::Bulk);
    if is_in {
        let mut buf = vec![0u8; length];
        loop {
            let r = match ttype {
                TransferType::Interrupt => h.read_interrupt(addr, &mut buf, IN_POLL),
                _ => h.read_bulk(addr, &mut buf, IN_POLL),
            };
            match r {
                Ok(n) => {
                    buf.truncate(n);
                    return UrbResult::ok(n, buf);
                }
                Err(rusb::Error::Timeout) => {
                    if unlinked.load(Ordering::SeqCst) {
                        return UrbResult::fail(-ECONNRESET);
                    }
                }
                Err(e) => return UrbResult::fail(errno(e)),
            }
        }
    } else {
        let r = match ttype {
            TransferType::Interrupt => h.write_interrupt(addr, data, OUT_TIMEOUT),
            _ => h.write_bulk(addr, data, OUT_TIMEOUT),
        };
        match r {
            Ok(n) => UrbResult::ok(n, Vec::new()),
            Err(e) => UrbResult::fail(errno(e)),
        }
    }
}

fn ret_submit(seqnum: u32, r: &UrbResult) -> Vec<u8> {
    let mut f = Vec::with_capacity(48 + r.data.len());
    f.extend(USBIP_RET_SUBMIT.to_be_bytes());
    f.extend(seqnum.to_be_bytes());
    f.extend(0u32.to_be_bytes()); // devid
    f.extend(0u32.to_be_bytes()); // direction
    f.extend(0u32.to_be_bytes()); // ep
    f.extend(r.status.to_be_bytes());
    f.extend(r.actual_length.to_be_bytes());
    f.extend(0i32.to_be_bytes()); // start_frame
    f.extend(0i32.to_be_bytes()); // number_of_packets
    f.extend(0i32.to_be_bytes()); // error_count
    f.extend([0u8; 8]);
    f.extend_from_slice(&r.data);
    f
}

fn ret_unlink(seqnum: u32, status: i32) -> Vec<u8> {
    let mut f = Vec::with_capacity(48);
    f.extend(USBIP_RET_UNLINK.to_be_bytes());
    f.extend(seqnum.to_be_bytes());
    f.extend([0u8; 12]);
    f.extend(status.to_be_bytes());
    f.extend([0u8; 24]);
    f
}

// ---------------------------------------------------------------------------
// Connections
// ---------------------------------------------------------------------------

async fn urb_loop(socket: TcpStream, dev: Arc<Exported>, cancel: Arc<Cancel>) -> io::Result<()> {
    let (mut rd, mut wr) = socket.into_split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let writer = tokio::spawn(async move {
        while let Some(frame) = rx.recv().await {
            if wr.write_all(&frame).await.is_err() {
                break;
            }
        }
    });
    let pending: Arc<Mutex<HashMap<u32, Arc<AtomicBool>>>> = Default::default();

    let result = loop {
        let mut hdr = [0u8; 48];
        tokio::select! {
            r = rd.read_exact(&mut hdr) => {
                if let Err(e) = r { break Err(e); }
            }
            _ = cancel.notify.notified() => {
                break Err(io::Error::new(io::ErrorKind::ConnectionAborted, "device unplugged"));
            }
        }
        let command = be32(&hdr[0..4]);
        let seqnum = be32(&hdr[4..8]);
        let direction = be32(&hdr[12..16]);
        let ep = be32(&hdr[16..20]) as u8;
        match command {
            USBIP_CMD_SUBMIT => {
                let length = be32(&hdr[24..28]) as i32;
                let number_of_packets = be32(&hdr[32..36]) as i32;
                let mut setup = [0u8; 8];
                setup.copy_from_slice(&hdr[40..48]);
                let is_in = direction == USBIP_DIR_IN;
                let mut data = Vec::new();
                if !is_in && length > 0 {
                    data.resize(length as usize, 0);
                    if let Err(e) = rd.read_exact(&mut data).await {
                        break Err(e);
                    }
                }
                let iso = number_of_packets > 0;
                if iso {
                    let mut skip = vec![0u8; 16 * number_of_packets as usize];
                    if let Err(e) = rd.read_exact(&mut skip).await {
                        break Err(e);
                    }
                }
                let unlinked = Arc::new(AtomicBool::new(false));
                pending.lock().unwrap().insert(seqnum, unlinked.clone());
                let dev = dev.clone();
                let tx = tx.clone();
                let pending = pending.clone();
                tokio::task::spawn_blocking(move || {
                    let r = perform_urb(&dev, is_in, ep, length.max(0) as usize, &setup, &data, iso, &unlinked);
                    debug!(
                        "{} seq {seqnum} ep {ep:#04x} {} len {length} -> status {} actual {}",
                        dev.label, if is_in { "IN" } else { "OUT" }, r.status, r.actual_length
                    );
                    let still_pending = pending.lock().unwrap().remove(&seqnum).is_some();
                    if still_pending {
                        let _ = tx.send(ret_submit(seqnum, &r));
                    }
                });
            }
            USBIP_CMD_UNLINK => {
                let unlink_seqnum = be32(&hdr[20..24]);
                let status = match pending.lock().unwrap().remove(&unlink_seqnum) {
                    Some(flag) => {
                        flag.store(true, Ordering::SeqCst);
                        -ECONNRESET
                    }
                    None => 0, // already completed
                };
                debug!("{} unlink seq {unlink_seqnum} -> {status}", dev.label);
                let _ = tx.send(ret_unlink(seqnum, status));
            }
            other => {
                break Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("unexpected USB/IP command {other}"),
                ));
            }
        }
    };

    for (_, flag) in pending.lock().unwrap().drain() {
        flag.store(true, Ordering::SeqCst);
    }
    drop(tx);
    let _ = writer.await;
    result
}

async fn handle_connection(mut socket: TcpStream, peer: SocketAddr, registry: Arc<Registry>) -> io::Result<()> {
    let mut hdr = [0u8; 8];
    socket.read_exact(&mut hdr).await?;
    let version = u16::from_be_bytes([hdr[0], hdr[1]]);
    let code = u16::from_be_bytes([hdr[2], hdr[3]]);
    if version != USBIP_VERSION {
        debug!("{peer}: protocol version {version:#06x} (expected {USBIP_VERSION:#06x}), continuing");
    }
    match code {
        OP_REQ_DEVLIST => {
            let reply = registry.devlist_reply();
            socket.write_all(&reply).await?;
            Ok(())
        }
        OP_REQ_IMPORT => {
            let mut b = [0u8; 32];
            socket.read_exact(&mut b).await?;
            let busid = cstr(&b);
            let cancel = Arc::new(Cancel::default());
            let Some(dev) = registry.take(&busid, cancel.clone()) else {
                warn!("{peer}: import of {busid} refused (unknown or already in use)");
                let mut reply = Vec::new();
                reply.extend(USBIP_VERSION.to_be_bytes());
                reply.extend(OP_REP_IMPORT.to_be_bytes());
                reply.extend(ST_NA.to_be_bytes());
                socket.write_all(&reply).await?;
                return Ok(());
            };
            let mut reply = Vec::new();
            reply.extend(USBIP_VERSION.to_be_bytes());
            reply.extend(OP_REP_IMPORT.to_be_bytes());
            reply.extend(ST_OK.to_be_bytes());
            dev.info.write(&mut reply);
            if let Err(e) = socket.write_all(&reply).await {
                registry.release(&dev);
                return Err(e);
            }
            info!("{peer} imported {} ({})", dev.label, dev.info.busid);
            let r = urb_loop(socket, dev.clone(), cancel.clone()).await;
            registry.release(&dev);
            match &r {
                Ok(()) => info!("{peer} released {}", dev.label),
                Err(_) if cancel.flag.load(Ordering::SeqCst) => {
                    info!("{peer} dropped {} after unplug", dev.label)
                }
                Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => {
                    info!("{peer} detached {}", dev.label)
                }
                Err(e) => warn!("{peer} connection for {} failed: {e}", dev.label),
            }
            Ok(())
        }
        other => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("unexpected USB/IP op {other:#06x}"),
        )),
    }
}

async fn accept_loop(listener: TcpListener, registry: Arc<Registry>) {
    loop {
        match listener.accept().await {
            Ok((socket, peer)) => {
                let registry = registry.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_connection(socket, peer, registry).await {
                        if e.kind() != io::ErrorKind::UnexpectedEof {
                            warn!("{peer}: {e}");
                        }
                    }
                });
            }
            Err(e) => {
                warn!("accept failed: {e}");
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bus scanning
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Filter {
    all: bool,
    vids: HashSet<u16>,
}

impl Filter {
    fn wants(&self, vid: u16) -> bool {
        self.all || self.vids.contains(&vid)
    }
}

/// Identity of a plugged device. A phone that reboots into another mode gets
/// a new product id and usually a new address, so it shows up as a new key.
#[derive(Hash, PartialEq, Eq, Clone, Copy, Debug)]
struct DevKey {
    bus: u8,
    address: u8,
    port: u8,
    vid: u16,
    pid: u16,
}

async fn rescan_loop(filter: Filter, excludes: Excludes, poll: Duration, registry: Arc<Registry>) {
    let mut exported: HashMap<DevKey, String> = HashMap::new(); // key -> busid
    let mut failed: HashMap<DevKey, Instant> = HashMap::new();
    let mut serials: HashMap<DevKey, Option<String>> = HashMap::new();
    let mut excluded_now: HashSet<DevKey> = HashSet::new();
    let retry_after = Duration::from_secs(5);

    loop {
        let excluded = excludes.current();
        match rusb::devices() {
            Err(e) => warn!("libusb cannot list devices: {e}"),
            Ok(list) => {
                let mut present: HashSet<DevKey> = HashSet::new();
                for dev in list.iter() {
                    let desc = match dev.device_descriptor() {
                        Ok(d) => d,
                        Err(_) => continue,
                    };
                    if desc.class_code() == rusb::constants::LIBUSB_CLASS_HUB {
                        continue;
                    }
                    if !filter.wants(desc.vendor_id()) {
                        continue;
                    }
                    let key = DevKey {
                        bus: dev.bus_number(),
                        address: dev.address(),
                        port: dev.port_number(),
                        vid: desc.vendor_id(),
                        pid: desc.product_id(),
                    };
                    present.insert(key);
                    let busid = format!("{}-{}-{}", key.bus, key.address, key.port);

                    // Exclusions can change at any time: release an exported
                    // device the moment it is listed, re-export when unlisted.
                    let serial_known = serials.get(&key).cloned();
                    let is_excluded = |serial: Option<&str>| {
                        Excludes::matches(&excluded, &key, serial, &busid)
                    };
                    if let Some(sn) = &serial_known {
                        if is_excluded(sn.as_deref()) {
                            if let Some(b) = exported.remove(&key) {
                                if let Some(d) = registry.remove(&b) {
                                    info!("released {} to the Mac (excluded)", d.label);
                                    if let Some(c) = d.conn.lock().unwrap().as_ref() {
                                        c.cancel();
                                    }
                                }
                            } else if excluded_now.insert(key) {
                                info!(
                                    "not exporting {:04x}:{:04x} {} (excluded)",
                                    key.vid,
                                    key.pid,
                                    sn.as_deref().unwrap_or("")
                                );
                            }
                            continue;
                        }
                        if excluded_now.remove(&key) {
                            info!("reclaiming {:04x}:{:04x} {}", key.vid, key.pid, sn.as_deref().unwrap_or(""));
                        }
                    }

                    if exported.contains_key(&key) {
                        continue;
                    }
                    if let Some(t) = failed.get(&key) {
                        if t.elapsed() < retry_after {
                            continue;
                        }
                    }
                    let handle = match dev.open() {
                        Ok(h) => h,
                        Err(e) => {
                            if !failed.contains_key(&key) {
                                warn!(
                                    "not exporting {:04x}:{:04x}: open failed: {e} (will retry)",
                                    key.vid, key.pid
                                );
                            }
                            failed.insert(key, Instant::now());
                            continue;
                        }
                    };
                    let serial = handle.read_serial_number_string_ascii(&desc).ok();
                    serials.insert(key, serial.clone());
                    if is_excluded(serial.as_deref()) {
                        // handle drops here: nothing stays claimed
                        if excluded_now.insert(key) {
                            info!(
                                "not exporting {:04x}:{:04x} {} (excluded)",
                                key.vid,
                                key.pid,
                                serial.as_deref().unwrap_or("")
                            );
                        }
                        continue;
                    }
                    match export(&dev, &desc, handle, serial.as_deref()) {
                        Ok(x) => {
                            failed.remove(&key);
                            info!("exported {} as bus id {}", x.label, x.info.busid);
                            exported.insert(key, x.info.busid.clone());
                            registry.insert(Arc::new(x));
                        }
                        Err(e) => {
                            if !failed.contains_key(&key) {
                                warn!(
                                    "not exporting {:04x}:{:04x}: {e} (will retry)",
                                    key.vid, key.pid
                                );
                            }
                            failed.insert(key, Instant::now());
                        }
                    }
                }

                let gone: Vec<DevKey> = exported
                    .keys()
                    .filter(|k| !present.contains(k))
                    .copied()
                    .collect();
                for key in gone {
                    let busid = exported.remove(&key).unwrap();
                    if let Some(d) = registry.remove(&busid) {
                        info!("unplugged {} ({})", d.label, busid);
                        if let Some(c) = d.conn.lock().unwrap().as_ref() {
                            c.cancel();
                        }
                    }
                }
                for d in registry.devices.lock().unwrap().values() {
                    d.retry_claims();
                }
                failed.retain(|k, _| present.contains(k));
                serials.retain(|k, _| present.contains(k));
                excluded_now.retain(|k| present.contains(k));
            }
        }
        tokio::time::sleep(poll).await;
    }
}

#[tokio::main]
async fn main() {
    // env_logger matches directives by prefix, so keep the default at warn and
    // raise only our own crate.
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("warn,usbip_share=info"))
        .format_timestamp_secs()
        .init();
    let args = Args::parse();

    if args.list_vendors {
        for (vid, name) in ANDROID_VENDORS {
            println!("{vid:04x}  {name}");
        }
        return;
    }

    let mut vids: HashSet<u16> = ANDROID_VENDORS.iter().map(|(v, _)| *v).collect();
    vids.extend(args.vids.iter().copied());
    let filter = Filter { all: args.all, vids };

    let listener = match TcpListener::bind(args.bind).await {
        Ok(l) => l,
        Err(e) => {
            error!("cannot listen on {}: {e}", args.bind);
            std::process::exit(1);
        }
    };
    info!(
        "USB/IP server on {} exporting {}",
        args.bind,
        if args.all {
            "every USB device".to_string()
        } else {
            format!("{} Android vendor ids (--list-vendors, --vid, --all)", filter.vids.len())
        }
    );
    info!("in the Docker VM: usbip list -r host.docker.internal");

    let excludes = Excludes {
        fixed: args.excludes.clone(),
        file: expand_home(&args.exclude_file),
    };
    info!(
        "exclusions: {} from the command line, file {} ({})",
        excludes.fixed.len(),
        excludes.file.display(),
        if excludes.file.exists() { "present" } else { "absent" }
    );

    let registry = Arc::new(Registry::default());
    tokio::spawn(accept_loop(listener, registry.clone()));
    rescan_loop(filter, excludes, Duration::from_millis(args.poll_ms.max(100)), registry).await;
}
