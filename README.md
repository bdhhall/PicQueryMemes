# PicQuery (PicQueryMemes Fork)

[中文](README_zh.md)| English

![cover_en](assets/cover_en.jpg)

🔍 Search for your local images with natural language, running completely offline. For example, "a laptop on the desk", "sunset by the sea", "kitty in the grass", and so on.  
Search images by picking a photo from your gallery
- Totally free, NO in-app purchases
- Support both English and Chinese
- Indexing and searching of images works completely offline without worrying about privacy
- Show results in less than 1 second when searching for 8,000+ photos
- Wait for indexing on the first time you launch, and search immediately afterward
- **New:** "Load More Results" button to fetch additional results beyond the initial set
- **New:** "Surprise Me" 🎲 roulette button to discover random indexed photos

## Installation

<a href='https://play.google.com/store/apps/details?id=me.grey.picquery&pcampaignid=pcampaignidMKT-Other-global-all-co-prtnr-py-PartBadge-Mar2515-1'><img style="width:130px" src='./assets/google-play-badge-en.png'/></a>

- Google Play - Search for “PicQuery”
- Download APK from [Release](https://github.com/greyovo/PicQuery/releases)
- If you have trouble accessing the above resources, please see [here](README_zh.md##其他方式)

> 🍎 For iOS users, please refer to _[Queryable](https://apps.apple.com/us/app/queryable-find-photo-by-text/id1661598353)_ ([Code](https://github.com/mazzzystar/Queryable)), the inspiration behind this application, developed by [@mazzzystar](https://github.com/mazzzystar/Queryable).

## Implementation

> Thanks to [@mazzzystar](https://github.com/mazzzystar) and [@Young-Flash](https://github.com/Young-Flash) for their assistance during the development. The discussion can be viewed [here](https://github.com/mazzzystar/Queryable/issues/12).

_PicQuery_ is powered by OpenAI's [CLIP model](https://github.com/openai/CLIP). and Apple's [mobile clip](https://github.com/apple/ml-mobileclip)


First, the images to be searched are encoded into vectors using an image encoder and stored in a database. The text provided by the user during the search is also encoded into a vector. The encoded text vector is then compared with the indexed image vectors to calculate the similarity. The top K images with the highest similarity scores are selected as the query results.


### Indexing Architecture — What Gets Stored

When images are indexed, **only vector embeddings are stored** — no image content, category text, or OCR results are saved alongside the vector. The index contains:

| Field | Type | Description |
|-------|------|-------------|
| `photoId` | `Long` | MediaStore ID of the photo |
| `albumId` | `Long` | Album/bucket ID the photo belongs to |
| `data` | `FloatArray` | 512-dim (CLIP) or 256-dim (MobileCLIP) HNSW vector |

There is no per-image text metadata (tags, captions, categories) stored in the index. All semantic understanding comes entirely from the vector embedding at search time. This means:
- Extending the index to store additional metadata (tags, faces, OCR text) would require schema changes and re-indexing.
- Filtering by content category is not natively supported — it would require running a classifier at query time or enriching the index with category labels.

---

## Developer Guide

### Prerequisites

| Requirement | Version |
|-------------|---------|
| Android Studio | Hedgehog 2023.1+ (or newer) |
| JDK | 17 |
| Min Android SDK | 29 (Android 10) |
| Target Android SDK | 35 (Android 15) |
| Kotlin | 2.3.0 |
| Gradle | 8.9.3 |

### ⚠️ Required Assets (Not Included in Repo)

The ML model files are **not committed to the repository** due to their size. You must obtain them separately before building.

#### Option A: CLIP (Default)
Model files required in `app/src/main/assets/`:
- `clip-image-int8.ort`
- `clip-text-int8.ort`

**Obtain via Colab notebook** (recommended): [Open Jupyter Notebook](https://colab.research.google.com/drive/1bW1aMg0er1T4aOcU5pCNYVgmVzBJ4-x4#scrollTo=hPscj2wlZlHb)

**Or download pre-built from Google Drive**: [CLIP models](https://drive.google.com/drive/folders/1VHgEvYyKsiVte8-lywD8qS8SfgcvMc3z?usp=drive_link)

#### Option B: MobileCLIP (Faster, smaller)
Model files required in `app/src/main/assets/`:
- `vision_model.ort`
- `text_model.ort`

**Download from Google Drive**: [MobileCLIP models](https://drive.google.com/drive/folders/1HgGDfsHHIlDK_Fx0Spnujxt51SgguNCq?usp=drive_link)

Then switch the DI module in `app/src/main/java/me/grey/picquery/common/AppModules.kt`:
```kotlin
// Change this line:
val AppModules = listOf(..., modulesCLIP, ...)
// To:
val AppModules = listOf(..., modulesMobileCLIP, ...)  // or modulesMobileCLIPv2
```

### Build Instructions

```bash
# Clone and open in Android Studio, then:
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (requires signing config)
./gradlew installDebug           # Build and install on connected device
```

### Linting

```bash
./gradlew ktlintFormat           # Auto-format Kotlin code
./gradlew ktlintCheck            # Check Kotlin style (CI)
./gradlew detekt                 # Static analysis
```

### Testing

```bash
./gradlew test                   # Unit tests (no device needed)
./gradlew connectedAndroidTest   # Instrumented tests (device/emulator needed)
```

---

## Features

### Search
- **Text search**: natural language queries are encoded to vectors and matched against the index
- **Image search**: pick a photo from the gallery to find visually similar ones
- **Load More**: tap "Load More Results" at the bottom of search results to retrieve additional matches (increases the search K incrementally, up to 300 results max)

### Surprise Me 🎲
- Tap the **Surprise** button on the home screen to discover 20 random indexed photos
- Results are shown in the same grid view as search results
- Requires at least one album to be indexed first

### Similar Photos
- Groups near-identical photos (duplicates, burst shots) using Union-Find clustering
- Configurable similarity threshold (default: 0.96)

---

## Extensibility Notes

This section is intended for developers (including AI coding agents) extending this project.

### Architecture Overview

```
UI Layer (Compose/MVVM)
  └── SearchViewModel / HomeViewModel / DisplayViewModel
Domain Layer
  └── ImageSearcher → SearchOrchestrator → EmbeddingService
Data Layer
  └── ObjectBoxEmbeddingRepository (vector store, HNSW index)
  └── PhotoRepository (MediaStore access)
ML Layer
  └── CLIP / MobileCLIP (ONNX Runtime)
```

### Key Extension Points

| Task | Where to Start |
|------|---------------|
| Add new ML model | Add new module in `feature/`, implement `ImageEncoder`/`TextEncoder`, create a new Koin module |
| Add metadata to index | Extend `ObjectBoxEmbedding` with new fields, update `EmbeddingService.encodePhotoList` |
| New search screen/feature | Add a new `ViewModel`, `Screen` composable, and `Routes` entry |
| Increase load-more cap | Change `MAX_EXTENDED_TOP_K` in `SearchViewModel` |
| Change roulette count | Change `ROULETTE_COUNT` in `HomeViewModel` |
| Add category filtering | Add a `category: String?` field to `ObjectBoxEmbedding`, populate during indexing via a classifier |

### Potential Blockers for Local APK Development

1. **ML model files are required** — the app will crash at launch with `FileNotFoundException` if not present. Download them before building (see [Required Assets](#️-required-assets-not-included-in-repo)).
2. **Android 10+ required** — `minSdk = 29`. Older devices are not supported.
3. **MediaStore permissions** — the app requires `READ_MEDIA_IMAGES` (Android 13+) or `READ_EXTERNAL_STORAGE` (older). Grant these when prompted.
4. **First-time indexing is slow** — indexing all photos takes time (~35ms/image). Budget ~6 minutes for 10,000 photos on a mid-range device.
5. **ObjectBox version lock** — ObjectBox 4.x with `@HnswIndex` is required. Downgrading will break vector search.
6. **Koin DI wiring** — when adding new features, always update `AppModules.kt` to register new classes.

---

## Build & Run Clip

To build this project, you need to obtain a quantized CLIP model.

Run the scripts in this [jupyter notebook](https://colab.research.google.com/drive/1bW1aMg0er1T4aOcU5pCNYVgmVzBJ4-x4#scrollTo=hPscj2wlZlHb) step by step. When you run into the _"You are done"_ section, you should get the following model files in `./result ` directory:

- `clip-image-int8.ort`
- `clip-text-int8.ort`
> If you don't want to run the scripts, you may directly download them from [Google Drive](https://drive.google.com/drive/folders/1VHgEvYyKsiVte8-lywD8qS8SfgcvMc3z?usp=drive_link).

## Build & Run mobile-clip

To build this project, you need to obtain a quantized CLIP model.


- `vision_model.ort`
- `text_model.ort`

> download them from [Google Drive](https://drive.google.com/drive/folders/1HgGDfsHHIlDK_Fx0Spnujxt51SgguNCq?usp=drive_link).

Put them into `app\src\main\assets` and you're ready to go.

## Choose module
val AppModules = listOf(viewModelModules, dataModules, modulesCLIP, domainModules) pick the module you want，Clip pair to modulesCLIP module， mobile-clip pair to modulesMobileCLIP module

## FAQ
### Issue 1
java.lang.RuntimeException: java.lang.reflect.InvocationTarget Exception
> Don't forget to add model files to `app\src\main\assets` directory

### Issue 2
java.io.FileNotFoundException: clip-image-int8.ort
> Make sure the model files are in the correct directory, if you are using mobile-clip, make sure you are using the correct model files, and change the module to modulesMobileCLIP

## Acknowledgment

- [mazzzystar/Queryable](https://github.com/mazzzystar/Queryable)
- [Young-Flash](https://github.com/Young-Flash)
- [IacobIonut01/Gallery](https://github.com/IacobIonut01/Gallery)

## License

This project is open-source under an MIT license. All rights reserved.
