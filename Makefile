COMPOSE ?= docker compose
DC = $(COMPOSE) -f docker-compose.yml -f docker-compose.mac-usb.yml
USBIP_EXCLUDE = $(HOME)/.usbip-share/exclude

.PHONY: help build host-services dev-up attach dev-down models doctor usb-release usb-reclaim usb-status runpod-up runpod-down runpod-status clean

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
RUNPOD_POD_ID ?= your-pod-id
runpod-up:        ## start the RunPod GPU pod and switch the agent to it (needs runpodctl auth)
	runpodctl pod start $(RUNPOD_POD_ID)
	@sed -i '' 's/^LLM_BACKEND=.*/LLM_BACKEND=runpod/' .env
	@$(DC) up -d dev && echo "agent -> RunPod. Endpoint warms up in a few minutes; watch with: make runpod-status"
runpod-down:      ## switch the agent back to local Ollama and STOP the pod (stops billing)
	@sed -i '' 's/^LLM_BACKEND=.*/LLM_BACKEND=local/' .env
	@$(DC) up -d dev
	runpodctl pod stop $(RUNPOD_POD_ID) && echo "pod stopped; billing paused. Disk kept; restart with make runpod-up"
runpod-status:    ## pod state and the backend the agent is using
	@grep -m1 '^LLM_BACKEND=' .env
	runpodctl pod get $(RUNPOD_POD_ID) 2>/dev/null || runpodctl pod list
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
