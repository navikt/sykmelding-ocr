# sykmelding-ocr
Bibliotek for OCR parsing av papirsykmeldinger

## Usage

```kotlin
val parser = ParseSykmeldingOcr(Ocr(), TemplateMatcher()) // create once: it reads every template
val sykmelding = parser.doOcr(pdfBytes)                     // the PDF in memory; doOcr(File) also works
```

The lib needs a writable `java.io.tmpdir` (`/tmp` by default). On first use it copies the templates and the
OCR model there, since they must be files, and JavaCPP extracts its native libraries (OpenCV, Tesseract) to
`~/.javacpp/cache`, or to `java.io.tmpdir` when the home folder is not writable. The PDF itself is not written to disk.

## Template images

The application opens on a **Templates** view listing the templates in `lib/src/main/resources/templates`
(run it from the repository root). They are packaged in the lib jar; `TemplateMatcher()` matches against
`TemplateLibrary.fromResources()`, a copy of them made from the `templates/index.txt` the build writes,
since a jar's folders cannot be listed.
Use **New template** to create a version folder (for example `03.2019`), then
**Add page** to create and edit each page. Double-click a page to reopen its editor.
Select a template and use **Import page** to add an existing page JSON instead.
Choose the page number when adding or importing a page.
The JSON and its template image are copied into the chosen `page-N` folder,
with local relative paths and an updated `mal.json`; the originals are kept.
Use **Delete page** on a selected page to remove its layout, page folder and images
after confirmation. Its entry is removed from `mal.json`; other pages keep their
existing names and order.
Use **Change page number** to renumber a selected page, including its folder,
JSON filename and entry in `mal.json`. Pages are ordered numerically, and numbers
already in use are rejected. Open editors keep their unsaved changes and save to
the new location.

```text
templates/
  03.2019/
    mal.json
    page-1/
      page-1.json
      template.png
    page-2/
      page-2.json
      template.png
```

`mal.json` contains the template name and ordered relative page references:
`{"navn": "03.2019", "pages": ["page-1/page-1.json", "page-2/page-2.json"]}`.
Each page JSON contains its fields, grid settings and template image reference.
New pages start without an image; import an image or PDF page in the editor and save.

Before marking regions, use **Rotate + straighten** in the page editor to detect
the page orientation and small scan angle. Confirm the corrected preview and save
the template to keep its straightened image. Rotation is blocked once coordinate-based
regions or image edits exist, to avoid moving them away from their content.
When creating a template from a PDF, use **Straighten** on a page or
**Rotate + straighten included pages**; creation waits for rotation detection to finish.

The bucket views have no bucket names built in: type the bucket name. The last one used is remembered
in your local Java Preferences, and buckets you have downloaded from are offered in the list.

Use the GCP bucket test view (**Test bucket PDFs…**) to test PDFs against the templates. It aligns
PDF pages to template pages by full-page features, and the form lines must line up; each PDF page is
rendered and its features detected once for all template pages it is tested against. The resolutions
the PDF is rendered, matched and read (OCR) at can be chosen or typed in; production's are the default.
So can the matching methods, to try other combinations than production's (SIFT, ratio test, MAGSAC++):

- **Keypoints**: SIFT, ORB (the 10 000 strongest), AKAZE, KAZE or BRISK, found on the pages and the templates
  alike. Binary descriptors (ORB, AKAZE, BRISK) are matched by Hamming distance, the others by L2.
- **Match filter**: Lowe's ratio test, cross-check (mutual nearest), or GMS (grid-based motion statistics).
- **Homography**: MAGSAC++, RANSAC, LMedS, RHO, or USAC (default, fast, accurate, PROSAC).

Whatever the methods, a page matches when at least 8 matches, and 40 % of them, agree with the homography and the
form lines then line up. A match agrees with the homography when it lies within the **tolerance** (*within … px*,
in template pixels at 400 DPI, where 1 px is 0.06 mm) of where the homography puts it. **Auto**, production's, is one
feature pixel but never less than 3 pixels (OpenCV's default): a scan is never exactly a homography of the form, and
at 1 pixel too few matches agree for even the right page. Type another number of pixels to try it; a larger one
accepts looser fits, so the aligned page that OCR reads may be a little off. Render 400 / features 400 / AKAZE / GMS /
RANSAC within Auto (3 px) is the matcher as it was before these could be chosen; it takes about five times as long as
production's settings. Reports record the tolerance each run used; reports saved before it was recorded used one
feature pixel, with no minimum.
On the test PDFs (two forms and a tilted 150-DPI scan of one), SIFT, AKAZE, KAZE and BRISK
match them all with the ratio test or GMS, and SIFT with every homography method; cross-check misses with BRISK,
and ORB finds too few distinctive keypoints on these forms to match more than one of them (with GMS).
**As production** sets the resolutions and methods back to production's.
Use **Choose templates…** to test against one, several or all templates; the choice is
remembered.
When the bucket cannot be listed (expired credentials, no network), **Show downloads**
lists the files already downloaded from it instead; they are previewed and tested as
usual, and **Load** lists the bucket again.

Saving a template writes its current image to `template.png` beside the template
JSON, overwriting the previous image.
