# Erbjudandekartan

En liten Python-webbapp som visar butiker från Fyndkartan och öppnar aktuella erbjudanden i en dialogruta ovanpå startsidan.

## Första uppstart

Installera Python 3.11+ och kör `setup.ps1` en gång i PowerShell. Skriptet hämtar Fyndkartan, installerar `uv`, synkar API:ts beroenden och installerar webbappens paket. Om GitHubs ZIP-hämtning blockeras, använd länken som skriptet visar och packa upp repot bredvid appen under namnet `fyndkartan`.

## Starta båda samtidigt

Kör `start.ps1` i PowerShell eller använd genvägen på skrivbordet. Det startar Fyndkartan API på port 8700 och webbappen på `http://127.0.0.1:8501`, och öppnar webappen i webbläsaren. API:t lyssnar på datorns nätverkskort så att Android-appen kan ansluta över ditt privata Wi-Fi. Stäng tjänsterna som skriptet startat med Ctrl+C i PowerShell-fönstret. Tjänster som redan körs lämnas ifred.

Erbjudandekartan skapar automatiskt ett slumpat konto via Fyndkartan API första gången den ansluter. Den sparar inloggningen lokalt i `.fyndkartan_account.json` och använder samma konto nästa gång. Du behöver alltså inte skapa konto manuellt. Kontot är bara till för den lokala Fyndkartan-instansen; det använder ingen riktig e-postadress och skickar inga e-postmeddelanden. API-adressen kan ändras i sidopanelen.

På startsidan kan du välja **Använd min plats för att sortera närmast först**. Webbläsaren frågar om platsåtkomst när du trycker på knappen. Positionen används bara i den aktuella sessionen för att sortera butiker; avståndet visas ungefärligt fågelvägen. Om platsåtkomst nekas fungerar stadsfiltret fortfarande.

## Android-app

Android Studio-projektet ligger i mappen `android`. Se [android/README.md](android/README.md) för att bygga mobilappen. Appen kör Fyndkartan API lokalt på telefonen.

## Datatäckning

Appen visar kedjorna ICA, Coop, Willys, Hemköp, Lidl och City Gross. Fyndkartan kan returnera produktnamn, märke, rå och normaliserad förpackningsstorlek, erbjudandepris, jämförpris per kg/l/st, flerpacksmekanik, medlemspris, kategori, ursprung, bild och sista giltighetsdag. Appen visar dessa fält när källan innehåller dem. Om förpackningsstorlek saknas säger appen det uttryckligen i stället för att gissa om priset gäller ett eller flera paket.

Fyndkartan har erbjudandestöd för ICA, Coop, Willys, Hemköp och City Gross. Lidl-butiker visas, men Fyndkartan har ännu inte en Lidl-erbjudandeadapter.
