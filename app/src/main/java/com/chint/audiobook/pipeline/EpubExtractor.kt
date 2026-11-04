package com.chint.audiobook.pipeline

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class ExtractedChapter(
  val index: Int,
  val title: String,
  val rawText: String
)

data class ExtractedBook(
  val title: String,
  val author: String,
  val coverBytes: ByteArray? = null,
  val chapters: List<ExtractedChapter>,
  val language: String? = null
)

object EpubExtractor {

  /**
   * Round 10 (Task 2, B10): chapter titles come from the TOC — the NCX map first
   * (desktop authority, `_titles`), gaps filled from an EPUB3 nav document
   * (`_nav_titles`, NCX wins where both exist). Chapter text runs through the
   * desktop `_to_text` rules in [ChapterHtmlRules.intendedName]: body crop, strip
   * of script/style/comments/head, full entity decode, paragraph blocks. The first
   * block is dropped when it repeats the resolved title.
   */
  fun extractEpub(epubFile: File): ExtractedBook {
    ZipFile(epubFile).use { zip ->
      val opfPath = findOpfPath(zip) ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml missing or malformed")
      val opfEntry = zip.getEntry(opfPath) ?: throw IllegalArgumentException("OPF file not found in ZIP: $opfPath")

      val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

      var title = "Unknown Title"
      var author = "Unknown Author"
      var language: String? = null
      var coverHref: String? = null
      var navHrefRelative: String? = null
      val manifest = mutableMapOf<String, String>() // id -> href
      val manifestProperties = mutableMapOf<String, String>() // id -> properties
      val spine = mutableListOf<String>() // idref list

      zip.getInputStream(opfEntry).use { input ->
        val doc = parseXmlDocument(input)
        val allElements = doc.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
          val elem = allElements.item(i) as? Element ?: continue
          val localName = elem.tagName.substringAfter(':').lowercase()
          when (localName) {
            "title" -> {
              val text = elem.textContent?.trim().orEmpty()
              if (text.isNotEmpty() && title == "Unknown Title") title = text
            }
            "creator" -> {
              val text = elem.textContent?.trim().orEmpty()
              if (text.isNotEmpty() && author == "Unknown Author") author = text
            }
            "language" -> {
              val text = elem.textContent?.trim().orEmpty()
              if (text.isNotEmpty() && language == null) language = text
            }
            "item" -> {
              val id = elem.getAttribute("id")
              val href = elem.getAttribute("href")
              if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = opfDir + href
                val properties = elem.getAttribute("properties")
                if (properties.isNotBlank()) {
                  manifestProperties[id] = properties
                  if (properties.split(' ').contains("nav") && navHrefRelative == null) {
                    navHrefRelative = href
                  }
                  if (properties.contains("cover-image")) {
                    coverHref = opfDir + href
                  }
                }
              }
            }
            "itemref" -> {
              val idref = elem.getAttribute("idref")
              if (idref.isNotBlank()) {
                spine.add(idref)
              }
            }
          }
        }
      }

      // TOC titles: NCX is the authority, EPUB3 nav fills gaps (desktop merge).
      val ncxTitles = readNcxTitles(zip, opfDir)
      val navTitles = navHrefRelative?.let { rel -> readNavTitles(zip, opfDir, rel) } ?: emptyMap()
      val tocTitles = buildMap {
        putAll(navTitles)
        putAll(ncxTitles) // NCX wins wherever both name the same chapter
      }

      // Read cover image if present
      val coverBytes = coverHref?.let { href ->
        zip.getEntry(href)?.let { entry ->
          zip.getInputStream(entry).use { it.readBytes() }
        }
      }

      // Extract raw text for each spine item
      val extractedChapters = mutableListOf<ExtractedChapter>()
      var chapterIndex = 1

      for (idref in spine) {
        val chapterHref = manifest[idref] ?: continue
        val chapterEntry = zip.getEntry(chapterHref) ?: continue

        val htmlContent = zip.getInputStream(chapterEntry).use { it.bufferedReader().readText() }
        val blocks = ChapterHtmlRules.htmlToBlocks(htmlContent)
        val chapterTitle = ChapterHtmlRules.resolveChapterTitle(
          tocTitles[chapterHref] ?: "",
          htmlContent,
          chapterIndex
        )
        val rawText = ChapterHtmlRules.dropTitleBlock(blocks, chapterTitle).joinToString("\n\n")

        if (rawText.isNotBlank()) {
          extractedChapters.add(
            ExtractedChapter(
              index = chapterIndex++,
              title = chapterTitle,
              rawText = rawText
            )
          )
        }
      }

      return ExtractedBook(
        title = title,
        author = author,
        coverBytes = coverBytes,
        chapters = extractedChapters,
        language = language
      )
    }
  }

  private fun findOpfPath(zip: ZipFile): String? {
    val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
    return zip.getInputStream(containerEntry).use { input ->
      val doc = parseXmlDocument(input)
      val allElements = doc.getElementsByTagName("*")
      for (i in 0 until allElements.length) {
        val elem = allElements.item(i) as? Element ?: continue
        if (elem.tagName.substringAfter(':').lowercase() == "rootfile") {
          val fullPath = elem.getAttribute("full-path")
          if (fullPath.isNotBlank()) return fullPath
        }
      }
      null
    }
  }

  /**
   * Desktop `_titles`: every NCX `navPoint` contributes navLabel text for its
   * `content@src` (fragment stripped). First label per href wins.
   */
  private fun readNcxTitles(zip: ZipFile, opfDir: String): Map<String, String> {
    val ncxEntry = zip.getEntry("${opfDir}toc.ncx")
      ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".ncx", ignoreCase = true) }
      ?: return emptyMap()
    return zip.getInputStream(ncxEntry).use { input ->
      val doc = parseXmlDocument(input)
      val titles = mutableMapOf<String, String>()
      val allElements = doc.getElementsByTagName("*")
      for (i in 0 until allElements.length) {
        val elem = allElements.item(i) as? Element ?: continue
        if (elem.tagName.substringAfter(':').lowercase() == "navpoint") {
          var label = ""
          var src: String? = null
          val childNodes = elem.childNodes
          for (j in 0 until childNodes.length) {
            val child = childNodes.item(j) as? Element ?: continue
            val childTag = child.tagName.substringAfter(':').lowercase()
            if (childTag == "navlabel") {
              val subText = child.getElementsByTagName("*")
              for (k in 0 until subText.length) {
                val t = subText.item(k) as? Element ?: continue
                if (t.tagName.substringAfter(':').lowercase() == "text") {
                  label = t.textContent?.trim().orEmpty()
                  break
                }
              }
            } else if (childTag == "text" && label.isEmpty()) {
              label = child.textContent?.trim().orEmpty()
            } else if (childTag == "content" && src == null) {
              src = child.getAttribute("src")
            }
          }
          val href = src?.substringBefore('#')
          if (label.isNotEmpty() && !href.isNullOrBlank()) {
            val key = "$opfDir$href"
            if (key !in titles) titles[key] = label
          }
        }
      }
      titles
    }
  }

  /**
   * Desktop `_nav_titles` (EPUB3): every `<a href>` in the nav document contributes
   * its text for the href (fragment stripped).
   */
  private fun readNavTitles(zip: ZipFile, opfDir: String, navHrefRelative: String): Map<String, String> {
    val navEntry = zip.getEntry(opfDir + navHrefRelative) ?: return emptyMap()
    val navDir = opfDir + (navHrefRelative.takeIf { it.contains("/") }?.substringBeforeLast('/')?.plus("/") ?: "")
    val titles = mutableMapOf<String, String>()
    zip.getInputStream(navEntry).use { input ->
      val doc = parseXmlDocument(input)
      val allElements = doc.getElementsByTagName("*")
      for (i in 0 until allElements.length) {
        val elem = allElements.item(i) as? Element ?: continue
        if (elem.tagName.substringAfter(':').lowercase() == "a") {
          val href = elem.getAttribute("href").substringBefore('#')
          val text = elem.textContent?.trim().orEmpty()
          if (text.isNotEmpty() && href.isNotBlank()) {
            titles["$navDir$href"] = text
          }
        }
      }
    }
    return titles
  }

  private fun parseXmlDocument(input: InputStream): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      try {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      } catch (_: Throwable) {
        // Ignored on platforms where these specific features aren't configurable
      }
    }
    return factory.newDocumentBuilder().parse(input)
  }
}
