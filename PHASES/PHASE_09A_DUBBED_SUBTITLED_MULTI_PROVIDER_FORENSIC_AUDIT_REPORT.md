# CINESTREAM USER APP — PHASE 09A
# FORENSIC AUDIT REPORT: DUBBED/SUBTITLED VARIANTS, MULTI-PROVIDER SEARCH & PLAYBACK ARCHITECTURE

**Execution Mode:** STRICT READ-ONLY FORENSIC AUDIT  
**Audit Target:** CineStream User App Working Tree  
**Package / App ID in Build:** `com.aistudio.cinestream.bceiai` (Permitted: `com.aistudio.cinestream.xyzabc`)  
**Timestamp:** 2026-10-10  
**Phase:** 09A  

---

## 1. ملخص تنفيذي (Executive Summary)

تم إجراء تدقيق جنائي برمجي شامل وقائم على الأدلة المباشرة (Evidence-Based Forensic Audit) لبنية تطبيق CineStream User App، بهدف تحديد المتطلبات المعمارية الدقيقة لإدماج وسائط المحتوى **المدبلج (Dubbed)** و**المترجم (Subtitled)** مع محركات البحث متعددة المزودين (Multi-Provider Search)، وشاشات التفاصيل، ومشدّي التشغيل (المضمن والمستقل)، وإدارة الحلقات، ونظام التحميل والتخزين المحلي.

### خلاصة النتائج الرئيسية:
1. **توقف البحث عند أول مزود ناجح [Verified in current code]:**
   في `SearchManagedExtensionsUseCase.kt` (السطور 70-76)، تتوقف حلقة البحث فور إرجاع أول مزود لنتائج غير فارغة (`if (searchResult.items.isNotEmpty()) return Result.success(searchResult)`). لا يتم استعلام المزودين اللاحقين ولا يتم تجميع المرشحين.
2. **فقدان هوية المزود والصفحات المصدرية عند التنقل [Verified in current code]:**
   في `SearchBarDropdown.kt` (السطر 172) و `SearchScreen.kt` (السطر 644) و `AppNavigation.kt` (السطور 1045-1050)، يمرر مسار التنقل فقط `mediaId: String` و `isMovie: Boolean`. يتم إسقاط هوية المزود (`extensionId`)، ورابط الصفحة المصدرية (`url`)، وعزم النمط (`variant intent`) بالكامل.
3. **الخلط التام في حقل `ManagedExtension.language` [Verified in current code]:**
   حقل `language` في `ManagedExtension` يعبر حصرياً عن **لغة فهرسة موقع المزود** لترتيب استعلامات البحث (العربية أولاً أم الإنجليزية أولاً في `MediaTitleResolver.kt`). إعادة استخدامه كنمط صوتي أو ترجمة سيكسر دقة مطابقة العناوين فوراً.
4. **تداخل كاش التشغيل وقواعد بيانات التحميل [Verified in current code]:**
   - مفتاح الكاش في `ServerStateStore` و `PlaybackResolutionCache` مبني على `"$rawTitle-$isMovie-$season-$episode"` ويفتقر تماماً لنمط المحتوى (مدبلج/مترجم) ولغة الصوت. الانتقال بين المدبلج والمترجم يعيد تشغيل النسخة المخزنة السابقة بالخطأ.
   - جدول `download_items` في Room (الإصدار 9) لا يحتوي على حقول `mode` أو `language`، ومفتاح المعرف الأساسي `id` للحلقات هو `"${seriesId}_${epId}"` مما يؤدي لتصادم واستبدال الحلقة المترجمة بالحلقة المدبلجة على القرص وفي قاعدة البيانات.
5. **طبيعة المواقع العربية وخداع محدد المسارات [Verified in current code]:**
   المواقع العربية (EgyDead, QFilm, WitAnime...) لا تقدم مسارات صوتية متعددة داخل ملف HLS واحد؛ بل توفر **صفحات ويب أو سيرفرات مستقلة تماماً** للمحتوى المدبلج والمترجم. لذلك لا يمكن لمحدد مسار ExoPlayer الداخلي التبديل بينهما.
6. **عزل الأنمي الصارم [Verified in current code]:**
   محتوى الأنمي مترجم فقط وحصري لمزودي الأنمي (`witanime`, `anime4up`, `animeblkom`). يجب منع إقحام مسارات الدبلجة أو البحث في مزودي الأفلام العامة له منعاً باتاً.

---

## 2. مخطط تدفق البحث الحالي والتحليل الميداني (Search & Extension Runtime Trace)

### 2.1 مخطط تدفق البحث الحالي (Current Search Flow)
```
[User Input Query]
       │
       ▼
[SearchViewModel.queryFlow (debounce 500ms)]
       │
       ├───────────────────────────────────────────────────────┐
       ▼                                                       ▼
[TMDB multiSearch]                               [ManagedMediaOrchestrator.searchMedia]
(Returns Movies & Series)                                      │
       │                                                       ▼
       │                                        [SearchManagedExtensionsUseCase.execute]
       │                                                       │
       │                                                       ▼
       │                                        [ManagedExtensionResolver.resolveEligibleExtensions]
       │                                        (Filtered: ACTIVE, capabilities, priority desc)
       │                                                       │
       │                                                       ▼
       │                                        [For Each Candidate Loop]
       │                                        runtime.search(candidate)
       │                                        ──> IF items.isNotEmpty() ──> [RETURN FIRST MATCH!]
       │                                        (Subsequent providers IGNORED)
       │                                                       │
       ├───────────────────────────────────────────────────────┘
       ▼
[SearchViewModel Filtering & Mapping]
(Drops managed items if TMDB title matches!)
(Fabricates rating=8.0, runtime=120, releaseDate for managed items)
       │
       ▼
[SearchUiState (movieResults, seriesResults)]
       │
       ▼
[Click Result in SearchScreen / QuickSearch]
       │
       ▼
navController.navigate("movie_details/$id")  <── [DROPS providerId, sourceUrl, mode, candidates!]
```

### 2.2 الأدلة البرمجية الميدانية:
* **توقف البحث عند أول نتيجة ناجحة:**
  - *الملف:* `app/src/main/java/com/example/extension/managed/usecase/SearchManagedExtensionsUseCase.kt`
  - *الأسطر 70-76:*
    ```kotlin
    for (candidate in candidates) {
        val result = runtime.search(listOf(candidate), searchRequest)
        if (result.isSuccess) {
            val searchResult = result.getOrThrow()
            if (searchResult.items.isNotEmpty()) {
                return@withContext Result.success(searchResult) // [Verified: Stops here!]
            }
        }
    ```
* **تلف المرشحات وإسقاط مزودات الإضافات عند وجود تطابق TMDB:**
  - *الملف:* `app/src/main/java/com/example/ui/screens/search/SearchViewModel.kt`
  - *الأسطر 230 و 248:*
    ```kotlin
    .filterNot { item -> tmdbMovies.any { it.title.equals(item.title, ignoreCase = true) } }
    ...
    .filterNot { item -> tmdbSeries.any { it.title.equals(item.title, ignoreCase = true) } }
    ```
  - *الأثر:* إذا وجد TMDB العمل، يُحذف كائن `SearchMediaItem` القادم من الإضافة، ويُعرض فقط كائن TMDB الذي يملك معرف رقمي فقط، ولا يحتفظ برابط الصفحة المصدرية للمزود.
* **فبركة البيانات الوصفية (Fabricated Metadata):**
  - *الملف:* `app/src/main/java/com/example/ui/screens/search/SearchViewModel.kt`
  - *الأسطر 240 و 242 و 259:*
    ```kotlin
    rating = 8.0, // [Verified: Hardcoded rating!]
    runtime = 120 // [Verified: Hardcoded runtime!]
    ```
* **سرعة استجابة التغييرات الإدارية (Admin Propagation Latency):**
  - *الملف:* `app/src/main/java/com/example/extension/managed/repository/ManagedExtensionRealtimeSyncManager.kt`
  - *الأسطر 89-105 و 198-202 و 258-263:*
    تستمع الدالة عبر `addSnapshotListener` لـ `/managed_extensions` و `/config/search_order`. أي تعديل من لوحة التحكم يُحدث فوراً كائن الذاكرة `ManagedExtensionRuntimeRegistry.INSTANCE` خلال أجزاء من الثانية دون الحاجة لإعادة تشغيل التطبيق. [Verified in current code].

---

## 3. السلوك المطلوب للبحث متعدد المزودين (Required Multi-Provider Behavior)

لتحقيق متطلبات Phase 09B لاحقاً دون إرباك الواجهة:
1. **استعلام كافة المزودين المؤهلين بالتوازي (Fan-out Concurrent Querying):**
   - استبدال حلقة `for (candidate in candidates)` في UseCase بمجمع كوروتين `candidates.map { async { runtime.search(it, request) } }.awaitAll()`.
2. **الاستمرار بعد الأخطاء القابلة للتعافي:**
   - فشل مزود أو مواجهته لتحدي Cloudflare لا يجوز أن يوقف معالجة نتائج المزودين الآخرين.
3. **تجميع وحفظ مرشحي المصادر (Candidate Preservation):**
   - عدم اختزال النتيجة إلى مزود واحد. يجب ربط كل عمل بمصفوفة `sourceCandidates: List<ProviderSourceCandidate>`.
4. **توحيد الأعمال وإزالة التكرار (Deduplication without Collapsing):**
   - مطابقة النتائج بناءً على معرّف TMDB (أو الاسم وسنة الإنتاج) مع الاحتفاظ بجميع روابط المزودين المختلفة للعمل ذاته كخيارات بديلة.
5. **دمج تغطية الحلقات (Episode Coverage Merging):**
   - في حال وفر المزود A الحلقات 1-10 ووفر المزود B الحلقات 1-24، يتم توحيد قائمة الحلقات وعرض الحلقات 1-24 مع إسناد مصادر كل حلقة لمزودها الفعلي.
6. **منع الأنمي من مسارات الدبلجة نهائياً:**
   - فحص `targetContentType == ContentType.ANIME` وفرض `variant = SubtitledOnly`.

---

## 4. هوية المتغير الشكلي وعقد البيانات المقترح (Formal Variant Identity & Contract)

### 4.1 تحليل الحقول الحالية:
- **`ManagedExtension.language` [Verified in current code]:**
  - *المعنى الفعلي الحقيقي:* لغة واجهة موقع المزود وفهرسته (`"ar"` تعني أن الموقع يعتمد أسماء عربية كعناوين رئيسية).
  - *المستهلكون:* مستهلك حصرياً في `MediaTitleResolver.kt` (السطر 38) لتحديد أولوية صياغة استعلام البحث (`cleanArabic` أولاً أم `cleanTitle` أولاً).
  - *النتيجة:* **يحظر حظراً تاماً إعادة استخدام هذا الحقل للتعبير عن الدبلجة أو الترجمة.**

### 4.2 نموذج المتغير الشكلي المقترح (Proposed Formal Model):
```kotlin
// عقد البيانات المشترك المقترح للواجهات والتحميل والتشغيل
enum class MediaVariantMode {
    SUBTITLED, // مترجم
    DUBBED     // مدبلج
}

enum class VariantLanguage(val code: String, val displayNameRes: Int) {
    ARABIC("ar", R.string.lang_arabic),
    ENGLISH("en", R.string.lang_english),
    ORIGINAL("orig", R.string.lang_original)
}

data class MediaVariantIdentity(
    val canonicalWorkId: String,          // TMDB ID or canonical hash
    val contentType: ContentType,          // MOVIE, SERIES, ANIME
    val mode: MediaVariantMode,            // SUBTITLED or DUBBED
    val audioLanguage: VariantLanguage,    // Default ARABIC for dubbed
    val subtitleLanguage: VariantLanguage? // Default ARABIC for subtitled
) {
    val isAnime: Boolean get() = contentType == ContentType.ANIME

    init {
        // حماية عزل الأنمي: يمنع منعاً باتاً دبلجة الأنمي
        if (isAnime && mode == MediaVariantMode.DUBBED) {
            throw IllegalArgumentException("Anime is strictly subtitle-only. Dubbing is not supported.")
        }
    }
}

data class EpisodeVariantCoordinate(
    val variantIdentity: MediaVariantIdentity,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val providerId: String,
    val sourcePageUrl: String
)
```

### 4.3 قاعدة تجزئة دبلجة الحلقات (Episode-Level Granularity):
- وجود حلقة واحدة مدبلجة لا يعني أن المسلسل بكامله مدبلج.
- يتم ربط توفر الدبلجة بكل حلقة مفردة `(Season, Episode)`.

---

## 5. تتبع مسار التشغيل والتحميل (Playback & Download Trace)

### 5.1 مخطط تدفق التشغيل والتحميل (Playback & Download Flow)
```
[User Action: Play / Download]
       │
       ▼
[Check Local Storage] ──> File exists? ──> [Play / Manage Offline File]
       │ (No)
       ▼
[Check Playback Cache (ServerStateStore / PlaybackResolutionCache)]
       │
       ├─ Valid & Warm? ──> [ExoPlayer Stream Direct]
       │
       ▼ (Cold / Expired)
[ManagedMediaOrchestrator.discoverServers]
       │
       ├─ Iterates activeCandidates ──> Scraper search
       ├─ Resolves target Episode URL
       ├─ Calls scraper.discoverServers(targetUrl)
       │
       ▼
[Extract Playback / Download Source]
       │
       ├─ Inline Player / Standalone Player ──> ExoPlayer (Video size selection only)
       │
       └─ Download Initiated ──> [UnifiedDownloadCoordinator.download]
                                        │
                                        ▼
                                 [DownloaderHandoffAdapter]
                                        │
                                        ▼
                                 [DownloadRepository.addToDownloads]
                                 (Inserts into Room DB `download_items`)
                                        │
                                        ▼
                                 [AndroidDownloader / StreamDownloaderService]
```

### 5.2 الأدلة البرمجية الميدانية في مسار التشغيل والتحميل:
* **قصور محدد مسارات ExoPlayer (Media3 Track Selection):**
  - *الملف:* `app/src/main/java/com/example/ui/components/InlineDetailVideoPlayer.kt` (السطور 811-814 و 2006-2017) و `PlayerScreen.kt` (السطر 315).
  - *الأدلة:* التعديل يقتصر على `builder.setMaxVideoSize(maxVideoWidth, maxVideoHeight)`. لا يوجد أي تعامل مع `setPreferredAudioLanguage` أو `setPreferredTextLanguage`.
  - *السبب الفعلي:* مزودات الويب العربية تنشر روابط تدفق بمسار صوتي وحيد مدمج؛ التبديل بين المدبلج والمترجم يتطلب اختيار صفحة وسيرفر استخراج مختلف كلياً.
* **قصور مفاتيح التخزين على القرص في `MediaStorageUtils.kt`:**
  - *الملف:* `app/src/main/java/com/example/utils/MediaStorageUtils.kt`
  - *السطور 226 و 239:*
    ```kotlin
    val f = File(seriesDir, "s${season}e${episode}.$ext")
    val f = File(moviesDir, "$safeId.$ext")
    ```
  - *الأثر:* غياب تمييز النمط (مترجم/مدبلج) يؤدي إلى قيام ملف الحلقة المدبلجة بالكتابة فوق ملف الحلقة المترجمة على وحدة التخزين.

---

## 6. مواضع واجهة المستخدم في صفحة التفاصيل (Details UI Placement)

### 6.1 شاشة تفاصيل المسلسلات (`SeriesDetailsScreen`):
- *الملف:* `app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt`
- *الموضع الدقيق:* **بين قسم طاقم العمل (Cast) وقسم المواسم والحلقات (Seasons & Episodes)**.
- *الأسطر المحددة:* بعد السطر **1301** (`Spacer(modifier = Modifier.height(32.dp))`) وقبل السطر **1304** (`Text(stringResource(R.string.seasons_and_episodes))`).
- *المكون المقترح:* محدد نمط تفاعلي (مترجم / مدبلج) يظهر فقط إذا كان العمل يدعم أكثر من نمط، ويختفي تماماً لأعمال الأنمي.

### 6.2 شاشة تفاصيل الأفلام (`MovieDetailsScreen`):
- *الملف:* `app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt`
- *الموضع الدقيق:* **مباشرة أعلى أزرار التشغيل والتحميل (Action Buttons)** (بين السطرين **495** و **501**) أو أسفل بطاقة المعلومات الأساسية.

### 6.3 مشغلات الفيديو (Inline & Standalone):
- في المشغل المضمن (`InlineDetailVideoPlayer.kt`) والمشغل الكامل (`PlayerScreen.kt`): في قائمة خيارات المشغل (Gear / Settings Dialog) بجوار خيارات الجودة، أو كزر سريع لاختيار النسخة.

---

## 7. قاعدة بيانات Room ونظام الكاش (Persistent Cache & Room Architecture)

### 7.1 البنية الحالية لقاعدة البيانات:
- *الملف:* `app/src/main/java/com/example/data/db/AppDatabase.kt`
- *الإصدار الحالي:* **الإصدار 9 (`version = 9`)**.
- *الترحيلات السابقة الموثقة:* من `MIGRATION_1_2` حتى `MIGRATION_8_9` (التي قامت بترقية `library_items` بأمان).
- *جدول التحميلات الحالي (`download_items`):*
  ```sql
  CREATE TABLE `download_items` (
      `id` TEXT NOT NULL,
      `mediaId` TEXT NOT NULL,
      `title` TEXT NOT NULL,
      `posterUrl` TEXT NOT NULL,
      `isMovie` INTEGER NOT NULL,
      `quality` TEXT NOT NULL,
      `progress` REAL NOT NULL,
      `isPaused` INTEGER NOT NULL,
      `isCompleted` INTEGER NOT NULL,
      `fileSizeBytes` INTEGER NOT NULL,
      PRIMARY KEY(`id`)
  )
  ```

### 7.2 خطة الترحيل الآمنة المطلوبة مستقبلاً (Room Migration 9 -> 10):
للحفاظ على تحميلات المستخدمين الحالية دون حذف أي ملف:
1. ترقية إصدار قاعدة البيانات إلى **`version = 10`**.
2. كتابة كائن ترحيل `MIGRATION_9_10`:
   ```kotlin
   val MIGRATION_9_10 = object : Migration(9, 10) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE `download_items` ADD COLUMN `variantMode` TEXT NOT NULL DEFAULT 'subtitled'")
           db.execSQL("ALTER TABLE `download_items` ADD COLUMN `audioLanguage` TEXT NOT NULL DEFAULT 'ar'")
           db.execSQL("ALTER TABLE `download_items` ADD COLUMN `seasonNumber` INTEGER NOT NULL DEFAULT 0")
           db.execSQL("ALTER TABLE `download_items` ADD COLUMN `episodeNumber` INTEGER NOT NULL DEFAULT 0")
       }
   }
   ```
3. صيغة المفتاح المركب المقترح للتحميلات الجديدة:
   - للأفلام: `"${mediaId}_${variantMode}_${audioLanguage}"`
   - للحلقات: `"${seriesId}_s${season}e${episode}_${variantMode}_${audioLanguage}"`

### 7.3 بنية مفتاح الكاش الموحد المقترح (Unified Cache Key Contract):
```
Key Format:
canonicalWorkId : contentType : season : episode : variantMode : audioLang : providerId : quality

Example Movie:
"550:movie:1:1:dubbed:ar:egydead:1080p"

Example Series Episode:
"1399:series:2:5:subtitled:ar:qfilm:720p"

Example Anime Episode:
"85937:anime:1:12:subtitled:ar:witanime:auto"
```
*فصل مراجع الصفحات عن روابط البث المؤقتة:*
- يتم تخزين مرجع صفحة المزود (`sourcePageUrl`) بشكل دائم نسبياً (مدة 24 ساعة).
- يتم تخزين رابط البث الفعلي المستخرج (`streamUrl`) لمدة أقصاها ساعتان (`2 * 60 * 60 * 1000L`) مع فحص الصلاحية قبل التشغيل.

---

## 8. استهلاك عقد الإدارة وفايرستور (Admin & Firestore Contract Consumption)

### 8.1 المسارات والحقول المقروءة فعلياً:
1. **`/managed_extensions` (مجموعة كاملة):**
   - *الملف المستهلك:* `FirebaseFirestoreManagedExtensionDataSource.kt` و `ManagedExtensionRealtimeSyncManager.kt`.
   - *الحقول المقروءة:* `id`, `name`, `description`, `baseUrl`, `iconUrl`, `scraperKey`, `definitionVersion`, `minAppVersionCode`, `runtimeApiVersion`, `priority`, `language`, `contentTypes`, `status`, `enabled`, `updatedAt`.
   - *الغياب الصريح:* لا توجد حالياً أي حقول في وثائق الإضافات تميز الدبلجة (مثل `supportsDubbed`, `dubbedSearchOrder`).
2. **`/config/search_order` (وثيقة واحدة):**
   - *الملف المستهلك:* `SearchOrderDataSource.kt`.
   - *الحقول المقروءة:* `movie` (List<String>), `tv` (List<String>), `series` (List<String>), `anime` (List<String>).
   - *الغياب الصريح:* لا يوجد ترتيب مخصص للدبلجة (مثل `movie_dubbed`, `tv_dubbed`).
3. **التوافق التراجعي لـ `/extensions`:**
   - تم إيقاف وقفل قراءة `/extensions` القديمة نهائياً؛ الكود يعتمد حصرياً على `/managed_extensions` [Verified in current code].

### 8.2 تدقيق ملف قواعد الأمان المحلي (`firestore.rules`):
- *الملف:* `./firestore.rules`
- *الملاحظة الجنائية الخطيرة:* **مجموعة `/managed_extensions` غير مذكورة إطلاقاً داخل ملف `firestore.rules` المرفق مع المشروع!**
- في قواعد فايرستور، عدم ذكر المجموعة يعني حجب القراءة عنها افتراضياً (`Default Deny`).
- التطبيق يعتمد على الكاش المحلي `managed_extensions_lkg.json` عند فشل الاتصال بقواعد فايرستور، أو أن القواعد المنشورة على الخادم الفعلي تحتوي على استثناء لم يُدرج في المستودع المحلي [Requires actual Firebase configuration or rules verification].

---

## 9. مصفوفة تصنيف الأدلة الجنائية (Evidence Matrix)

| البند / النتيجة | الحالة والتصنيف | الدليل في الكود المصدر |
|---|---|---|
| توقف البحث عند أول مزود إضافات يعيد نتائج | [Verified in current code] | `SearchManagedExtensionsUseCase.kt:70-76` |
| إسقاط نتائج الإضافات عند تطابق عناوين TMDB | [Verified in current code] | `SearchViewModel.kt:230, 248` |
| فبركة تقييم 8.0 ومدة 120 دقيقة لنتائج الإضافات | [Verified in current code] | `SearchViewModel.kt:240, 242` |
| فقدان هوية المزود والصفحات المصدرية عند التنقل | [Verified in current code] | `SearchBarDropdown.kt:172`, `SearchScreen.kt:644` |
| حقل `ManagedExtension.language` هو لغة فهرسة الموقع | [Verified in current code] | `MediaTitleResolver.kt:38-41`, `ManagedExtensionMapper.kt:24` |
| كاش الخوادم يفتقر لنمط الدبلجة/الترجمة | [Verified in current code] | `ServerStateStore.kt:880-910`, `PlaybackResolutionCache.kt:72-79` |
| جدول التحميلات في Room (v9) لا يدعم أنماط الدبلجة | [Verified in current code] | `AppDatabase.kt:25, 58-71`, `DownloadItem.kt:6-18` |
| ملفات التحميل على القرص تتصادم بين المدبلج والمترجم | [Verified in current code] | `MediaStorageUtils.kt:226, 239` |
| ExoPlayer لا يبدل بين المدبلج والمترجم داخلياً | [Verified in current code] | `InlineDetailVideoPlayer.kt:811, 2006-2017` |
| حظر دبلجة الأنمي وتجريده لمزودي الأنمي فقط | [Verified in current code] | `AnimeScreen.kt:1012`, `WitanimeScraper.kt:88` |
| غياب قواعد `/managed_extensions` في `firestore.rules` المحلي | [Verified in current code] | `firestore.rules:1-364` |
| مطابقة القواعد المنشورة سحابياً للملف المحلي | [Requires actual Firebase rules verification] | لا يمكن التحقق منها دون صلاحيات لوحة تحكم فايرستور |
| سلوك تجربة المستخدم عند انقطاع الاتصال المفاجئ | [Requires runtime/device test] | يحتاج لاختبار أجهزة حية أو محاكاة شبكة |

---

## 10. الأسئلة المطلوب تأكيدها من تدقيق لوحة الإدارة (Admin Audit Questions)

1. هل توفر وثيقة `/config/search_order` في فايرستور أو لوحة الإدارة حقولاً إضافية مستقبلية مثل `movie_dubbed` و `series_dubbed`؟
2. هل تعتزم لوحة الإدارة إضافة حقل `supportedVariants` (مثل `["subtitled"]`, `["dubbed"]`, `["both"]`) داخل وثائق `/managed_extensions/{extensionId}`؟
3. هل قواعد فايرستور المنشورة سحابياً في الإنتاج تسمح بالقراءة العامة لـ `/managed_extensions` (`allow read: if true;`) لتعويض النقص في `firestore.rules` المحلي؟
4. ما هي المعايير المعتمدة في الإدارة للتعامل مع المواقع التي تقدم أفلاماً مدبلجة ومترجمة على نفس النطاق (مثل EgyDead) مقابل المواقع المتخصصة؟

---

## 11. الخلاصة وجاهزية المرحلة القادمة (Audit Sign-Off)

اكتمل التدقيق الجنائي بنجاح بنمط **القراءة الصارم (Strict Read-Only)** دون إجراء أي تعديل على ملفات المصدر أو قواعد البيانات أو البيئة التشغيلية. تم وضع اليد على كافة مواضع القصور المعمارية، وتحديد العقد البياني المشترك ومسارات الترحيل المطلوبة بدقة متناهية لضمان التنفيذ السلس في المراحل اللاحقة.
