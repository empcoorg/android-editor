COMPOSE ?= docker compose
DC = $(COMPOSE) -f docker-compose.yml -f docker-compose.mac-usb.yml
USBIP_EXCLUDE = $(HOME)/.usbip-share/exclude

.PHONY: help build host-services dev-up attach dev-down models doctor usb-release usb-reclaim usb-status cloud-up cloud-down cloud-status runpod-up runpod-down runpod-status ai-backend ai-pick ai-watch ai-beat clean

help:             ## list targets
	@grep -E '^[a-z-]+:.*## ' Makefile | sed 's/:.*## /\t/' | expand -t 18

build:            ## build the image (INSTALL_NDK=1 in .env adds the NDK)
	$(COMPOSE) build dev
host-services:    ## macOS: start the USB/IP server and Ollama on 127.0.0.1:11436 (idempotent)
	@bash macos/host-services.sh
dev-up: host-services ## start the dev container in the background (phones over USB/IP)
	$(DC) up -d dev && echo "dev container is up; open shells with: make attach"
attach:           ## shell in the running dev container
	$(DC) exec dev bash
dev-down:         ## stop the dev container and the USB sidecar
	$(DC) stop dev usbip && $(DC) rm -f dev usbip
# Cloud backend: local Ollama vs any OpenAI-compatible remote endpoint.
# Lifecycle comes from CLOUD_*_CMD in .env; the built-in runpodctl fallback
# covers the RunPod example out of the box, any other provider plugs in via
# the same hooks (or manage instances in your provider's console).
cloud-up:        ## switch the agent to the cloud backend (starts the instance if lifecycle is configured)
	@bin/ai-backend start
cloud-down:      ## switch back to local Ollama and stop the cloud instance (billing pauses)
	@bin/ai-backend stop
cloud-status:    ## which backend the agent uses + cloud instance state
	@bin/ai-backend status
runpod-up: cloud-up        # backwards-compatible alias (RunPod example)
runpod-down: cloud-down
runpod-status: cloud-status
# Capability-aware backend selection: local Ollama vs the cloud endpoint.
ai-backend:       ## show backend, models, instance state and idle timer
	@bin/ai-backend status
ai-pick:          ## choose backend for a task tier: make ai-pick TIER=large (default|large)
	@bin/ai-backend pick $(or $(TIER),default)
ai-beat:          ## record AI activity (resets the instance idle timer)
	@bin/ai-backend beat
ai-watch:         ## run the idle watcher: stops the instance after IDLE_MINUTES idle
	@bin/ai-backend watch
models:           ## download the two models into the host Ollama if missing
	$(DC) exec dev ai-setup
doctor:           ## tools, models and attached phones
	$(DC) exec dev android-doctor

usb-release:      ## hand a phone back to the Mac: make usb-release DEV=<serial|vid:pid|busid>
	@test -n "$(DEV)" || { echo "usage: make usb-release DEV=<serial|vid:pid|busid>"; exit 2; }
	@mkdir -p $(dir $(USBIP_EXCLUDE)) && touch $(USBIP_EXCLUDE)
	@awk -v d="$(DEV)" 'tolower($$1) == tolower(d) {f=1} END {exit !f}' $(USBIP_EXCLUDE) || echo "$(DEV)" >> $(USBIP_EXCLUDE)
	@echo "$(DEV) released to the Mac"
usb-reclaim:      ## take a released phone back: make usb-reclaim DEV=<serial|vid:pid|busid>
	@test -n "$(DEV)" || { echo "usage: make usb-reclaim DEV=<serial|vid:pid|busid>"; exit 2; }
	@test -f $(USBIP_EXCLUDE) && awk -v d="$(DEV)" 'tolower($$1) != tolower(d)' $(USBIP_EXCLUDE) > $(USBIP_EXCLUDE).tmp; mv -f $(USBIP_EXCLUDE).tmp $(USBIP_EXCLUDE)
	@echo "$(DEV) reclaimed for the container"
usb-status:       ## phones currently left to the Mac
	@echo "released to the Mac:"; cat $(USBIP_EXCLUDE) 2>/dev/null | sed '/^$$/d' || true

clean:            ## remove the containers, image and volumes (home dir, Gradle cache)
	$(DC) down -v --rmi local
