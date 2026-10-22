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
The JSON and its referenced images are copied into the chosen `page-N` folder,
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

Use the editor's **Reference points** tab to save distinctive image crops, then save
the page template. Use several reference crops spread across the page. The image
comparison view's **Apply reference images** locates the crops on the incoming page
(both pages as A4 at 400 DPI, then a consistent alignment of the crop centres). Reference
crops are found by template matching (OpenCV `TM_CCOEFF_NORMED`) at 80–120 % size, since
a form printed or scanned smaller or larger is still smaller or larger at 400 DPI; a crop
whose best correlation is below 0.6 counts as not found rather than being placed on the
best wrong spot. The GCP bucket test view aligns PDF pages to template pages by full-page
features, and the form lines must line up; each PDF page is rendered and its features
detected once for all template pages it is tested against.
Use **Choose templates…** to test against one, several or all templates; the choice is
remembered.
When the bucket cannot be listed (expired credentials, no network), **Show downloads**
lists the files already downloaded from it instead; they are previewed and tested as
usual, and **Load** lists the bucket again.
The **Compare** tab shows the page selected in the PDF tab with the template page it
matched (or came closest to) in an image comparison view: reference images are applied
on load, and you can apply the configuration, read fields (OCR), read the page number and
detect sykmelding. Pick another page or template page at the top, or double-click a row
in **Test result** (or use **Open in Compare**) to open that page × template page.

Saving a template writes its current image to `template.png` beside the template
JSON, overwriting the previous image. Field interiors can be optionally excluded
from feature matching; their borders remain available.
