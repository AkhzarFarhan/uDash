# uDash – Git workflow helpers
# Usage:
#   make push          – commit, fetch & rebase from main, push

BRANCH := $(shell git rev-parse --abbrev-ref HEAD)

# ── Push changes (main only) ────────────────────────────────────
.PHONY: push
push:
	@if [ "$(BRANCH)" != "main" ]; then \
		# echo "ERROR: You must be on the 'main' branch (current: $(BRANCH))"; \
		exit 1; \
	fi
	@read -p "Commit message: " msg; \
	git add -A; \
	git commit -m "$$msg"; \
	git pull --rebase origin main; \
	git push origin main; \
	@echo "✅ Pushed to main."
