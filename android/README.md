# Erbjudandekartan för Android

Android-appen kör Fyndkartan FastAPI-servern lokalt i appens Python-miljö. Den behöver inte ansluta till datorn och fungerar när datorn är avstängd. API-processen startar när appen öppnas; Android kan avsluta appen i bakgrunden, så synkronisering körs när du öppnar appen eller hämtar erbjudanden.

Appen innehåller ett startunderlag med butiker från `stores.db`. Den sparar sedan databasändringar i appens privata lagring på telefonen. Erbjudanden för ICA, Coop, Willys och Hemköp hämtas vid behov när du öppnar en butik. Lidl och City Gross visas med länkar till reklamblad där Fyndkartan inte har erbjudandedata.

På mobilen är återkommande bakgrundssynkronisering avstängd. ICA:s särskilda e-handelspris-crawl som använder Playwright-headless-browser kräver en separat desktop-browser och ingår därför inte i den inbäddade Android-servern. Vanliga API-anrop för butiker och erbjudanden är inbyggda.

## Bygg APK i Android Studio

1. Installera Android Studio med Android SDK och SDK Build Tools.
2. Öppna den här `android`-mappen som projekt och låt Gradle synkronisera. Vid första synkroniseringen hämtas Chaquopy och Python-paketen, så internetanslutning behövs.
3. Välj **Build > Build APK(s)**.
4. Installera APK-filen på Galaxy S21. Projektet är konfigurerat för Android 8.0 eller senare och inkluderar Galaxy S21:s 64-bitars ARM-arkitektur.

Appen lagrar kontot och den uppdaterade SQLite-databasen i sin privata lagring. Om du avinstallerar appen kan Android radera dessa lokala uppgifter.
