# STS_Sunrich_Demo_Testing

This repository contains the STS application in two directories:

- `frontend/` — Angular app. Set this as the Vercel Root Directory.
- `backend/` — Spring Boot API. Set this as the Railway Root Directory.

For local development, run `npm ci` and `npm start` in `frontend/`. The development proxy forwards `/api` to `http://localhost:8080`. The backend needs a MySQL database; configure its `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD` environment variables when hosting it.