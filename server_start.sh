#!/usr/bin/sh

uvicorn server.app:app --reload --host 0.0.0.0 --port 8080