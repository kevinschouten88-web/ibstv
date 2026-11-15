# IBStv in de App Store en Google Play: stappenplan

De app, de bouwstraten, de demo-server, de teksten en de afbeeldingen staan klaar.
Wat overblijft zijn de accounts en een paar klikken die alleen jij kunt doen.
Stuur nooit wachtwoorden of sleutels naar Claude; die zet je zelf in GitHub.

## 0. Eén keer, voor allebei

1. **D-U-N-S-nummer** voor je bedrijf aanvragen (gratis, via Apple: developer.apple.com/support/D-U-N-S). Duurt een paar dagen. Je KvK-gegevens heb je nodig.
2. **Privacypagina aanzetten:** GitHub > repository `ibstv` > Settings > Pages > Source: *Deploy from a branch* > Branch: `main`, map `/docs` > Save. Na een paar minuten staat hij op https://kevinschouten88-web.github.io/ibstv/
3. **Demo-server online zetten** (voor de keurders): volg `demo-server/LEESMIJ.md` (Cloudflare, gratis). Test daarna zelf: log in IBStv in met het adres van de Worker, gebruikersnaam `demo`, wachtwoord `ibstv-demo`.

## 1. Apple App Store

1. **Apple Developer Program** als organisatie: developer.apple.com/programs/enroll (99 euro per jaar, D-U-N-S nodig).
2. **App-ID vastleggen:** developer.apple.com > Certificates, IDs & Profiles > Identifiers > + > App IDs > App > Bundle ID (Explicit): `nl.ibstv.app`, beschrijving `IBStv`.
3. **App aanmaken:** appstoreconnect.apple.com > Apps > + > Nieuwe app: platform iOS, naam `IBStv`, hoofdtaal Nederlands, bundel-ID `nl.ibstv.app`, SKU `ibstv-ios`.
4. **API-sleutel maken:** App Store Connect > Gebruikers en toegang > Integraties > App Store Connect API > + . Rol: **Admin**. Download het `.p8`-bestand (kan maar één keer). Noteer de *Key ID* en de *Issuer ID*. Je *Team ID* staat op developer.apple.com > Account > Membership.
5. **In GitHub zetten:** repository `ibstv` > Settings > Secrets and variables > Actions > New repository secret, vier keer:
   - `APPLE_TEAM_ID` – je Team ID
   - `ASC_KEY_ID` – de Key ID
   - `ASC_ISSUER_ID` – de Issuer ID
   - `ASC_PRIVATE_KEY` – de hele inhoud van het `.p8`-bestand (open het met Kladblok)
6. **Bouwen en uploaden:** GitHub > Actions > *IBStv naar de App Store (TestFlight)* > Run workflow > versie `1.0.0`. Na ongeveer 15 minuten, plus 5 tot 30 minuten verwerking, staat de build in App Store Connect bij TestFlight.
7. **Zelf testen:** installeer de app *TestFlight* op je iPhone en voeg jezelf toe als interne tester. Dit is ook de makkelijkste manier om IBStv op je iPhone te zetten: geen Sideloadly en geen 7 dagen meer.
8. **Winkelpagina invullen** met `WINKELTEKSTEN.md` en de afbeeldingen `appstore-6.9-*.png`. Kies de build, vul *App-privacy* en *Opmerkingen voor de keurder* in, en klik op **Voeg toe voor beoordeling**.

## 2. Google Play

1. **Ontwikkelaarsaccount** als **organisatie**: play.google.com/console (25 dollar eenmalig, D-U-N-S nodig). Bij een persoonlijk account moet je eerst 14 dagen testen met minimaal 12 testers; bij een organisatie niet.
2. **Uploadsleutel in GitHub:** volg `LEESMIJ-uploadsleutel.txt` uit IBStv-uploadsleutel.zip (twee geheimen) en start daarna Actions > *IBStv Android-app bouwen* > Run workflow.
3. **Bestand ophalen:** github.com/kevinschouten88-web/ibstv/releases > *IBStv voor Android* > `IBStv-GooglePlay.aab`.
4. **App aanmaken** in de Play Console: naam `IBStv – IPTV-speler`, standaardtaal Nederlands, App, Gratis.
5. **App-content invullen** met `WINKELTEKSTEN.md` (privacybeleid, advertenties, app-toegang met de demo-login, contentclassificatie, doelgroep, gegevensbeveiliging).
6. **Winkelvermelding** invullen met de teksten en de `play-*.png`-afbeeldingen.
7. **Eerst intern testen:** Testen en releasen > Interne tests > Nieuwe release > `IBStv-GooglePlay.aab` uploaden. *Play App-ondertekening* staat standaard aan; laat dat zo. Voeg jezelf toe als tester en installeer IBStv via de link.
8. **Android TV toevoegen:** Geavanceerde instellingen > Formfactoren > Android TV.
9. **Productie:** Productie > Nieuwe release > dezelfde AAB > Ter beoordeling verzenden.

## Goed om te weten

- De Play-versie heet intern `nl.ibstv.app`. De versie die je nu zelf hebt geïnstalleerd heet anders. Verwijder die zodra je IBStv uit de Play Store of TestFlight hebt, anders staan er twee.
- Elke keer dat er iets in de repository verandert, bouwt GitHub de Android-app opnieuw. Een nieuwe versie naar Apple stuur je zelf met de knop uit stap 1.6.
- Eerst gratis uitbrengen is het makkelijkst. Het abonnement met 7 dagen proef bouwen we later in, met Apple's eigen betaalsysteem in de App Store en Google Play Billing in Google Play.
- Laat het privacybeleid en de antwoorden over gegevens controleren door een jurist voordat je gaat verkopen.
