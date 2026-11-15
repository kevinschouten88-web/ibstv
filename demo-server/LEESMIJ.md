# IBStv demo-server

Een kleine Xtream Codes-server met alleen legale inhoud, bedoeld voor de keurders van
Apple en Google (en om IBStv zelf te laten zien).

- Films: open films van de Blender Foundation (CC BY 3.0).
- Series: korte voorbeeldclips van Google.
- Zenders: openbare teststreams van Akamai, Mux en Apple.

## Online zetten (Cloudflare, gratis)

1. Log in op dash.cloudflare.com.
2. Ga naar **Workers & Pages** > **Create** > **Create Worker**, geef hem de naam `ibstv-demo` en klik op **Deploy**.
3. Klik op **Edit code**, vervang alle code door de inhoud van `worker.js` en klik op **Deploy**.
4. Het adres staat bovenaan, bijvoorbeeld `https://ibstv-demo.jouwnaam.workers.dev`.

## Inloggen in IBStv

| Veld | Waarde |
|---|---|
| Server-URL | het adres van de Worker |
| Gebruikersnaam | `demo` |
| Wachtwoord | `ibstv-demo` |
