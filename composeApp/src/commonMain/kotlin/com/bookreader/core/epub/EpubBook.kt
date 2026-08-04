package com.bookreader.core.epub

import com.bookreader.core.model.ContentDocument
import com.bookreader.core.xml.XmlElement
import com.bookreader.core.xml.parseXml
import com.bookreader.core.zip.RawInflater
import com.bookreader.core.zip.ZipArchive
import com.bookreader.core.zip.ZipFormatException
import com.bookreader.core.zip.decodeUtf8StrippingBom

data class EpubMetadata(
    val title: String,
    val authors: List<String>,
    val language: String?,
    val identifier: String?,
    val publisher: String?,
    val description: String?,
    val coverHref: String?,
) {
    val author: String? get() = authors.firstOrNull()
}

data class SpineItem(
    val id: String,
    val href: String,
    val mediaType: String?,
    val isLinear: Boolean,
)

data class TocEntry(
    val title: String,
    val href: String?,
    val level: Int,
    val spineIndex: Int?,
)

class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An opened EPUB.
 *
 * Chapters are decoded on demand rather than up front: a large book can hold
 * hundreds of spine items, and parsing them all to open the reader would stall
 * the first page for no benefit.
 */
class EpubBook internal constructor(
    private val archive: ZipArchive,
    val metadata: EpubMetadata,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
) {
    val chapterCount: Int get() = spine.size

    /** Raw bytes of a resource, given a path already resolved against the OPF. */
    fun resource(path: String): ByteArray? = archive.read(path)

    /** Cover image bytes, when the package declares one. */
    fun coverImage(): ByteArray? = metadata.coverHref?.let { archive.read(it) }

    /**
     * Parses spine item [index] into renderable blocks. Image references are
     * rewritten to full archive paths so the UI can pull them via [resource].
     */
    fun chapter(index: Int): ContentDocument {
        val item = spine.getOrNull(index)
            ?: return ContentDocument.Empty
        val xhtml = archive.read(item.href)?.decodeUtf8StrippingBom()
            ?: return ContentDocument(item.id, null, emptyList())

        val chapterDir = item.href.substringBeforeLast('/', "")
        val blocks = HtmlContentExtractor.extract(xhtml) { src ->
            val resolved = resolvePath(chapterDir, src)
            if (archive.contains(resolved)) resolved else null
        }
        val title = toc.firstOrNull { it.spineIndex == index }?.title
        return ContentDocument(id = item.id, title = title, blocks = blocks)
    }

    companion object {
        private const val CONTAINER_PATH = "META-INF/container.xml"

        fun open(bytes: ByteArray, inflater: RawInflater): EpubBook {
            val archive = try {
                ZipArchive.open(bytes, inflater)
            } catch (e: ZipFormatException) {
                throw EpubParseException("File is not a readable EPUB archive", e)
            }

            val opfPath = findOpfPath(archive)
            val opfXml = archive.read(opfPath)?.decodeUtf8StrippingBom()
                ?: throw EpubParseException("Package document missing at $opfPath")
            val opf = parseXml(opfXml).descendants("package").firstOrNull()
                ?: parseXml(opfXml)

            val opfDir = opfPath.substringBeforeLast('/', "")

            val manifest = readManifest(opf, opfDir)
            val metadata = readMetadata(opf, manifest, opfDir)
            val spine = readSpine(opf, manifest)
            if (spine.isEmpty()) throw EpubParseException("EPUB spine is empty — nothing to read")

            val toc = readToc(archive, opf, manifest, opfDir, spine)

            return EpubBook(archive, metadata, spine, toc)
        }

        private fun findOpfPath(archive: ZipArchive): String {
            val containerXml = archive.read(CONTAINER_PATH)?.decodeUtf8StrippingBom()
            if (containerXml != null) {
                val fullPath = parseXml(containerXml)
                    .descendants("rootfile")
                    .firstNotNullOfOrNull { it.attr("full-path") }
                if (!fullPath.isNullOrBlank()) return normalizePath(fullPath)
            }
            // Some malformed EPUBs omit container.xml; fall back to any .opf present.
            return archive.names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
                ?: throw EpubParseException("No package document (.opf) found in archive")
        }

        /** manifest id -> (resolved archive path, media type, properties) */
        private fun readManifest(
            opf: XmlElement,
            opfDir: String,
        ): Map<String, ManifestItem> {
            val out = LinkedHashMap<String, ManifestItem>()
            for (item in opf.descendants("item")) {
                val id = item.attr("id") ?: continue
                val href = item.attr("href") ?: continue
                out[id] = ManifestItem(
                    id = id,
                    path = resolvePath(opfDir, href),
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties").orEmpty(),
                )
            }
            return out
        }

        private fun readMetadata(
            opf: XmlElement,
            manifest: Map<String, ManifestItem>,
            opfDir: String,
        ): EpubMetadata {
            val meta = opf.child("metadata") ?: opf

            fun textOf(name: String): String? =
                meta.descendants(name).firstOrNull()?.allText()?.trim()?.takeIf { it.isNotEmpty() }

            val authors = meta.descendants("creator")
                .mapNotNull { it.allText().trim().takeIf(String::isNotEmpty) }
                .distinct()

            // Cover art is declared two different ways depending on EPUB version.
            val coverFromProperties = manifest.values
                .firstOrNull { it.properties.contains("cover-image") }?.path
            val coverFromMeta = meta.descendants("meta")
                .firstOrNull { it.attr("name") == "cover" }
                ?.attr("content")
                ?.let { manifest[it]?.path }
            val coverGuess = manifest.values.firstOrNull {
                it.mediaType?.startsWith("image/") == true &&
                    it.path.substringAfterLast('/').contains("cover", ignoreCase = true)
            }?.path

            return EpubMetadata(
                title = textOf("title") ?: "Untitled",
                authors = authors,
                language = textOf("language"),
                identifier = textOf("identifier"),
                publisher = textOf("publisher"),
                description = textOf("description"),
                coverHref = coverFromProperties ?: coverFromMeta ?: coverGuess,
            )
        }

        private fun readSpine(
            opf: XmlElement,
            manifest: Map<String, ManifestItem>,
        ): List<SpineItem> {
            val spineEl = opf.child("spine") ?: return emptyList()
            return spineEl.descendants("itemref").mapNotNull { ref ->
                val idref = ref.attr("idref") ?: return@mapNotNull null
                val item = manifest[idref] ?: return@mapNotNull null
                SpineItem(
                    id = item.id,
                    href = item.path,
                    mediaType = item.mediaType,
                    isLinear = ref.attr("linear")?.equals("no", ignoreCase = true) != true,
                )
            }
        }

        private fun readToc(
            archive: ZipArchive,
            opf: XmlElement,
            manifest: Map<String, ManifestItem>,
            opfDir: String,
            spine: List<SpineItem>,
        ): List<TocEntry> {
            val spineIndexByPath = spine.withIndex().associate { (i, item) -> item.href to i }

            // EPUB 3: a manifest item flagged with properties="nav".
            val navItem = manifest.values.firstOrNull { it.properties.split(" ").contains("nav") }
            if (navItem != null) {
                val navXml = archive.read(navItem.path)?.decodeUtf8StrippingBom()
                if (navXml != null) {
                    val entries = parseNavDocument(navXml, navItem.path, spineIndexByPath)
                    if (entries.isNotEmpty()) return entries
                }
            }

            // EPUB 2: an NCX referenced by the spine's toc attribute.
            val ncxId = opf.child("spine")?.attr("toc")
            val ncxItem = ncxId?.let { manifest[it] }
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncxItem != null) {
                val ncxXml = archive.read(ncxItem.path)?.decodeUtf8StrippingBom()
                if (ncxXml != null) {
                    val entries = parseNcx(ncxXml, ncxItem.path, spineIndexByPath)
                    if (entries.isNotEmpty()) return entries
                }
            }

            // No usable TOC: fall back to one entry per spine item.
            return spine.mapIndexed { i, item ->
                TocEntry("Chapter ${i + 1}", item.href, level = 0, spineIndex = i)
            }
        }

        private fun parseNavDocument(
            xml: String,
            navPath: String,
            spineIndexByPath: Map<String, Int>,
        ): List<TocEntry> {
            val baseDir = navPath.substringBeforeLast('/', "")
            val root = parseXml(xml)
            // Prefer the nav element with epub:type="toc"; fall back to the first.
            val nav = root.descendants("nav").firstOrNull { it.attr("type") == "toc" }
                ?: root.descendants("nav").firstOrNull()
                ?: return emptyList()

            val out = ArrayList<TocEntry>()
            fun walk(list: XmlElement, level: Int) {
                for (li in list.childrenNamed("li")) {
                    val anchor = li.descendants("a").firstOrNull()
                        ?: li.descendants("span").firstOrNull()
                    val title = anchor?.allText()?.trim().orEmpty()
                    val href = anchor?.attr("href")
                    val path = href?.let { resolvePath(baseDir, it) }
                    if (title.isNotEmpty()) {
                        out.add(TocEntry(title, path, level, path?.let { spineIndexByPath[it] }))
                    }
                    li.childrenNamed("ol").forEach { walk(it, level + 1) }
                    li.childrenNamed("ul").forEach { walk(it, level + 1) }
                }
            }
            nav.childrenNamed("ol").forEach { walk(it, 0) }
            nav.childrenNamed("ul").forEach { walk(it, 0) }
            if (out.isEmpty()) {
                nav.descendants("ol").firstOrNull()?.let { walk(it, 0) }
            }
            return out
        }

        private fun parseNcx(
            xml: String,
            ncxPath: String,
            spineIndexByPath: Map<String, Int>,
        ): List<TocEntry> {
            val baseDir = ncxPath.substringBeforeLast('/', "")
            val root = parseXml(xml)
            val navMap = root.descendants("navMap").firstOrNull() ?: return emptyList()

            val out = ArrayList<TocEntry>()
            fun walk(point: XmlElement, level: Int) {
                val title = point.child("navLabel")?.descendants("text")?.firstOrNull()
                    ?.allText()?.trim()
                    ?: point.descendants("text").firstOrNull()?.allText()?.trim()
                val href = point.child("content")?.attr("src")
                val path = href?.let { resolvePath(baseDir, it) }
                if (!title.isNullOrEmpty()) {
                    out.add(TocEntry(title, path, level, path?.let { spineIndexByPath[it] }))
                }
                point.childrenNamed("navPoint").forEach { walk(it, level + 1) }
            }
            navMap.childrenNamed("navPoint").forEach { walk(it, 0) }
            return out
        }
    }
}

internal data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String?,
    val properties: String,
)

/**
 * Resolves an EPUB-relative href against [baseDir], collapsing `.` and `..` and
 * percent-decoding the result. ZIP entry names are stored decoded, so a link to
 * `chapter%201.xhtml` must be turned back into `chapter 1.xhtml` to match.
 */
fun resolvePath(baseDir: String, href: String): String {
    val withoutFragment = href.substringBefore('#').substringBefore('?')
    val decoded = percentDecode(withoutFragment)
    if (decoded.isEmpty()) return normalizePath(baseDir)

    val parts = ArrayList<String>()
    if (!decoded.startsWith("/")) {
        baseDir.split('/').forEach { if (it.isNotEmpty() && it != ".") parts.add(it) }
    }
    for (segment in decoded.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

fun normalizePath(path: String): String = resolvePath("", path)

/** Percent-decodes a URI path, treating the bytes as UTF-8. */
fun percentDecode(input: String): String {
    if (!input.contains('%')) return input
    val out = ArrayList<Byte>(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '%' && i + 2 < input.length) {
            val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                out.add(hex.toByte())
                i += 3
                continue
            }
        }
        // Non-escaped characters are re-encoded so multi-byte text survives.
        c.toString().encodeToByteArray().forEach(out::add)
        i++
    }
    return out.toByteArray().decodeToString()
}
