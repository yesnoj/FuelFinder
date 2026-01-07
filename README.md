# FuelFinder 🚗⛽

<p align="center">
  <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Google%20Maps-4285F4?style=for-the-badge&logo=google-maps&logoColor=white" />
</p>

App Android per trovare i distributori di carburante più convenienti in Italia, con prezzi aggiornati quotidianamente dal database ufficiale del Ministero (MISE).

## 📱 Caratteristiche

- 🔍 **Ricerca intelligente**: Trova distributori in modalità "Percorso" (lungo la tua direzione) o "360°" (tutt'intorno)
- 💰 **Prezzi ufficiali**: Dati aggiornati quotidianamente dal MISE (Ministero dello Sviluppo Economico)
- 🗺️ **Integrazione Google Maps**: Visualizza i distributori sulla mappa e naviga direttamente
- ⛽ **Multi-carburante**: Supporta Diesel, Benzina, GPL e Metano
- 📏 **Distanze precise**: Mostra distanza in linea d'aria o distanza stradale reale (opzionale)
- 🔄 **Aggiornamento live**: Ricerca automatica mentre viaggi con frequenza personalizzabile
- 🎨 **Interfaccia moderna**: Material Design con tema chiaro e chip colorati

## 🚀 Come funziona

### Modalità di ricerca

#### 🧭 Modalità Percorso
Ideale per viaggi in autostrada - mostra solo i distributori:
- Davanti a te (nel tuo senso di marcia)
- Entro un corridoio laterale di 3 km
- Ordinati per distanza progressiva

#### 🔄 Modalità 360°
Perfetta in città - mostra tutti i distributori:
- In tutte le direzioni
- Entro il raggio selezionato
- Utile quando cerchi il prezzo migliore

### Impostazioni personalizzabili

- **Raggio di ricerca**: 5-50 km (default: 10 km)
- **Numero risultati**: 1-10 stazioni (default: 5)
- **Frequenza aggiornamento**: 1/3/5 minuti
- **Tipo distanza**: Linea d'aria (gratis) o stradale con Google Maps (consuma crediti API)

## 📊 Fonte dati

L'app utilizza i dati ufficiali del **MISE (Ministero dello Sviluppo Economico)**:
- Database completo di tutti i distributori italiani
- Obbligo di legge per i gestori di comunicare i prezzi
- Aggiornamento entro 8 giorni da ogni variazione
- Accesso tramite [API wrapper](https://github.com/dstmrk/prezzi-carburante) che semplifica l'uso dei dati pubblici

## 🔧 Configurazione sviluppo

### Prerequisiti

- Android Studio Arctic Fox o superiore
- Android SDK 24+ (Android 7.0)
- Kotlin 1.8+
- Account Google Cloud Platform (per le API Maps)

### Setup

1. **Clona il repository**
```bash
git clone https://github.com/tuousername/FuelFinder.git
cd FuelFinder
```

2. **Configura le API Key Google**

Crea il file `local.properties` nella root del progetto:
```properties
sdk.dir=/path/to/Android/sdk
GOOGLE_MAPS_API_KEY=TUA_API_KEY_QUI
BASE_URL=https://prezzi-carburante.onrender.com/
```

3. **Abilita le API necessarie in Google Cloud Console**
- Maps SDK for Android (obbligatoria)
- Distance Matrix API (opzionale, per distanze stradali)
- Directions API (opzionale)

4. **Compila ed esegui**
```bash
./gradlew assembleDebug
```

## 📦 Dipendenze principali

```gradle
// Google Maps e Location
implementation 'com.google.android.gms:play-services-maps:18.2.0'
implementation 'com.google.android.gms:play-services-location:21.0.1'

// Networking
implementation 'com.squareup.retrofit2:retrofit:2.9.0'
implementation 'com.squareup.retrofit2:converter-gson:2.9.0'

// Coroutines
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

// UI
implementation 'com.google.android.material:material:1.11.0'
```

## 🎯 Use Case tipici

### Viaggio in autostrada
1. Attiva modalità "Percorso"
2. Imposta raggio 20-30 km
3. Seleziona il tuo carburante
4. L'app mostrerà solo distributori davanti a te

### Ricerca in città
1. Attiva modalità "360°"
2. Imposta raggio 5-10 km  
3. Ordina per prezzo
4. Trova il più conveniente vicino a te

### Monitoraggio continuo
1. Premi "Avvia ricerca"
2. L'app aggiornerà automaticamente mentre guidi
3. I prezzi sono colorati per età: 🟢 freschi, 🟠 vecchi, 🔴 molto vecchi

## 💡 Suggerimenti

- **Risparmia dati**: Lascia disattivata la "distanza stradale" se non necessaria
- **Precisione bearing**: Su autostrada, mantieni velocità costante per migliore rilevamento direzione
- **Prezzi vecchi**: Diffida di prezzi non aggiornati da oltre 7 giorni (rossi)
- **Offline**: L'app richiede connessione internet per funzionare

## 🐛 Limitazioni note

- Solo distributori italiani
- I prezzi possono essere vecchi fino a 8 giorni (limite legale MISE)
- La modalità "Percorso" richiede movimento per rilevare la direzione
- L'API Google Maps per distanze stradali consuma crediti ($5/1000 richieste)

## 📄 Licenza

Questo progetto è rilasciato sotto licenza MIT. Vedi il file [LICENSE](LICENSE) per i dettagli.

## 🙏 Ringraziamenti

- [MISE](https://www.mise.gov.it/) per il database pubblico dei prezzi
- [@dstmrk](https://github.com/dstmrk/prezzi-carburante) per l'API wrapper
- Google Maps Platform per le mappe e navigazione

## 📧 Contatti

Per segnalazioni o suggerimenti, apri una [issue](https://github.com/tuousername/FuelFinder/issues) su GitHub.

---

**Nota**: Questa app è pensata per uso personale/sporadico (3-4 volte l'anno). Per uso commerciale o intensivo, considera di implementare una cache locale e ottimizzazioni per ridurre le chiamate API.
