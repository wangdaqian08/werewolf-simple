.PHONY: test test-unit test-e2e-ui test-e2e-integration test-all

test-unit:
	cd frontend && npm run test:unit

test-e2e-ui:
	cd frontend && npm run test:e2e:ui

test-e2e-integration:
	cd frontend && npm run test:e2e:integration

test-all:
	cd frontend && npm run test:all

test: test-all
