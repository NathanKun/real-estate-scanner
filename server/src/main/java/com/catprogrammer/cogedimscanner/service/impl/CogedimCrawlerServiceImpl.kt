package com.catprogrammer.cogedimscanner.service.impl

import com.catprogrammer.cogedimscanner.entity.Decision
import com.catprogrammer.cogedimscanner.entity.Lot
import com.catprogrammer.cogedimscanner.entity.Program
import com.catprogrammer.cogedimscanner.model.FormGetResult
import com.catprogrammer.cogedimscanner.model.NearbyProgram
import com.catprogrammer.cogedimscanner.model.RealEstateDeveloper
import com.catprogrammer.cogedimscanner.model.SearchResult
import com.catprogrammer.cogedimscanner.service.CogedimCrawlerService
import com.catprogrammer.cogedimscanner.service.LotService
import com.catprogrammer.cogedimscanner.service.ProgramService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.commons.io.IOUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.OutputStreamWriter
import java.lang.reflect.Type
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream


@Service
class CogedimCrawlerServiceImpl : CogedimCrawlerService {

    private val logger = LoggerFactory.getLogger(CogedimCrawlerServiceImpl::class.java)

    private val gson = Gson()
    private val type: Type = object : TypeToken<List<NearbyProgram>>() {}.type
    private val contactinfoC = "contact_info_known=true&contact_info[formCivility]=02&contact_info[formFirstName]=Not&contact_info[formLastName]=APerson&contact_info[formPhone]=0660600660&contact_info[formEmail]=not@an.email&contact_info[formLocation]=Paris&contact_info[formCity]=Paris&contact_info[formPostalCode]=75000&contact_info[formRegion]=%C3%8Ele-de-France&contact_info[formCountry]=France&contact_info[formDestination]=habiter"
    private val contactinfoK = "country=France&region=Normandie&department=Seine-Maritime&city=Rouen&postal_code=76000&email=not@an.emails&last_name=NOT&first_name=Aperson&civility=1&destination=habiter&location=Rouen&phone=0660606006"

    @Autowired
    private lateinit var programService: ProgramService
    @Autowired
    private lateinit var lotService: LotService

    /**
     * Send one or multiple requests to fetch all program from Cogedim.
     * Return a list of SearchResult object.
     */
    override fun requestSearchResults(): List<SearchResult> {
        val crawlData = mapOf(
                RealEstateDeveloper.COGEDIM to arrayOf(
                        "location=Hauts-de-Seine&department=Hauts-de-Seine&rooms=2,3,4,5", // 92
                        "location=ile-de-france&department=Paris&rooms=3,4,5", // Paris
                        "location=Le Vésinet&city=Le Vésinet&department=Yvelines&region=Île-de-France&rooms=2,3" // L’ Accord Parfait - 78 Le Vésinet
                ),
                RealEstateDeveloper.KAUFMANBROAD to arrayOf(
                        "location=Hauts-de-Seine&department=Hauts-de-Seine&rooms=2,3,4,5"
                )
        )
        val results = mutableListOf<SearchResult>()

        // for each developer
        for (entry in crawlData) {
            val developer = entry.key
            val dataArray = entry.value

            // for each data for post request
            for (data in dataArray) {
                logger.info("Crawling ${developer.name} $data")
                var page = 0
                var res: SearchResult?

                do {
                    res = fetchSearchResult(developer, page++, data)
                    if (res != null) {
                        res.developer = developer
                        results.add(res)
                    }
                } while (res?.hasMore != null && res.hasMore!!)

                Thread.sleep(5000)
            }
        }

        return results
        // return mutableListOf(gson.fromJson<SearchResult>(mokeSearchResult, SearchResult::class.javaObjectType))
    }

    /**
     * Parse a list of SearchResult object.
     * Find and save all programs and lots.
     */
    override fun parseSearchResults(results: List<SearchResult>, onlyRequestMissingBlueprintPdf: Boolean) {
        results.filter { it.results != null && it.results.size() > 0 }.forEach { searchResult ->
            val drupalSettings = searchResult.drupalSettings
            val nearbyPrograms: List<NearbyProgram> =
                    if (drupalSettings != null && drupalSettings.has("nearbyPrograms")) {
                        val nearbyProgramsJsonObject = drupalSettings.get("nearbyPrograms")
                        gson.fromJson<List<NearbyProgram>>(nearbyProgramsJsonObject, type)
                    } else {
                        emptyList()
                    }

            searchResult.results?.forEach { result ->
                var article = Jsoup.parse(result.asString)
                // val article = Jsoup.parse(mokeArticle)
                val program = parseSearchResultProgram(searchResult.developer!!, article, nearbyPrograms)

                if (program.developer == RealEstateDeveloper.KAUFMANBROAD) {
                    val conn = applyRequestHeaders(program.developer, URL(program.url).openConnection(), false)
                    val html = IOUtils.toString(
                            GZIPInputStream(conn.getInputStream()),
                            StandardCharsets.UTF_8
                    )
                    article = Jsoup.parse(html)
                }

                parseSearchResultLot(article, program, onlyRequestMissingBlueprintPdf)
                programService.save(program)
                logger.info("saved program ${program.programName} ${program.programNumber}")
            }
        }
    }

    /**
     * Parse the <article> dom object, find all lot and link to the given Program object and save
     */
    private fun parseSearchResultLot(article: Document, program: Program, onlyRequestMissingBlueprintPdf: Boolean) {
        val selector1 = when (program.developer) {
            RealEstateDeveloper.COGEDIM -> "v-expansion-panel[class^=regulation-] > *"
            RealEstateDeveloper.KAUFMANBROAD -> "#tab-regulation-17 div.lot-items"
            else -> throw Exception("RealEstateDeveloper ${program.developer} not supported")
        }
        val selector2 = when (program.developer) {
            RealEstateDeveloper.COGEDIM -> "v-expansion-panel v-expansion-panel-content[ripple]"
            RealEstateDeveloper.KAUFMANBROAD -> "div.lot-item"
            else -> throw Exception("RealEstateDeveloper ${program.developer} not supported")
        }

        val blueprintSelector = when (program.developer) {
            RealEstateDeveloper.COGEDIM -> "div.lot-details div.buttons v-btn[@click.native*=blueprint]"
            RealEstateDeveloper.KAUFMANBROAD -> "v-btn[@click.native*=blueprint]"
            else -> throw Exception("RealEstateDeveloper ${program.developer} not supported")
        }

        article.select(selector1).forEach { programTypeTag ->
            // for each lot type. eg: 3 pièces, 4 pièces, etc
            programTypeTag.select(selector2).forEach { lotTag ->
                // for each lot
                val lotNumber = lotTag.select("span.lot-lot_number").text()
                val surface = lotTag.select("span.lot-surface").text().replace(',', '.')
                val floor = lotTag.select("span.lot-floor").text()
                val price = lotTag.select("span.lot-price").text()

                val blueprintDownloadButton = lotTag.select(blueprintSelector)
                val blueprintDownloadButtonAttr = blueprintDownloadButton.attr("@click.native")
                val blueprintId = Regex("event, ?[0-9]{3,5}, ?([0-9]{3,5})").find(blueprintDownloadButtonAttr)?.groups?.get(1)?.value

                val oldLots = lotService.findAllByDeveloperAndProgramNumberAndLotNumber(program.developer, program.programNumber, lotNumber)

                // no need to request blueprint pdf if no blueprint id
                var requestPdf = blueprintId != null
                var oldPdfUrl: String? = null
                // check if already request pdf of this blueprint
                if (requestPdf) {
                    val lotHasBlueprintId = oldLots.find { l -> l.blueprintId == blueprintId!! }
                    if (lotHasBlueprintId?.pdfUrl != null) {
                        // if already have the pdf url, not requesting it again if onlyRequestMissingBlueprintPdf is true
                        requestPdf = !onlyRequestMissingBlueprintPdf
                        oldPdfUrl = lotHasBlueprintId.pdfUrl
                    }
                }

                val pdfUrl =
                        if (requestPdf) {
                            if (blueprintId != null) {
                                // pause 10 sec to reduce request per sec
                                // avoid being banned
                                Thread.sleep(10000)

                                val blueprintForm = fetchFormBlueprint(program.developer, program.programNumber, blueprintId)
                                if (blueprintForm != null) {
                                    program.developer.baseurl + parseFormGetResultGetPdfUrl(program.developer, blueprintForm)
                                } else null
                            } else null
                        } else {
                            oldPdfUrl
                        }

                val lotsHaveDecision = oldLots.find { l -> l.decision != Decision.NONE }
                val decision = lotsHaveDecision?.decision ?: Decision.NONE

                val lotsHasRemark = oldLots.find { l -> l.remark != null }

                val lot = Lot(null, lotNumber, surface, floor, price, blueprintId, pdfUrl,
                        lotsHasRemark?.remark, decision, null, null)
                program.lots.add(lot)
                lotService.save(lot)
                logger.info("saved lot ${lot.lotNumber}")
            }
        }
    }

    /**
     * Parse the <article> dom object, find it's program, instantiate, save and return the Program object
     */
    private fun parseSearchResultProgram(developer: RealEstateDeveloper, article: Document, nearbyPrograms: List<NearbyProgram>): Program {
        val programName = when (developer) {
            RealEstateDeveloper.COGEDIM -> article.select("div.info-box h2 span").text()
            RealEstateDeveloper.KAUFMANBROAD -> article.select("div.left-part h2 span").text()
            else -> throw Exception("RealEstateDeveloper $developer not supported")
        }
        val programId = article.select("article[is^=program-card-]").attr("class")
                .split(" ")
                .first { s -> s.startsWith("program-") }
                .replace("program-", "")
        var postalCode = article.select("span[itemprop=postalCode]").text()
        var address = article.select("span[itemprop=addressLocality]").text()

        if (postalCode == "") {
            val regexPostalCode = Regex("[0-9]{5}").find(address)?.value
            if (regexPostalCode != null) {
                postalCode = regexPostalCode
                address = address.replace(regexPostalCode, "").replace(" - ", "")
            }
        }

        val url = developer.baseurl +
                when (developer) {
                    RealEstateDeveloper.COGEDIM -> article.select("div.visual .gradient a.more-link").attr("href")
                    RealEstateDeveloper.KAUFMANBROAD -> article.select("a.program-link").attr("href")
                    else -> throw Exception("RealEstateDeveloper $developer not supported")
                }
        val imgUrl = developer.baseurl + article.select("div.visual img").attr("src")
                .replace("/styles/visual_327x188/public", "") // remove resize cogedim
                .replace("/styles/program_card/public", "") // remove resize kaufmanbroad
                .replace(Regex("\\?itok.*"), "") // remove param

        val leafletForm = fetchFormLeaflet(developer, programId)
        val pdfUrl = if (leafletForm != null) {
            Thread.sleep(10000)
            developer.baseurl + parseFormGetResultGetPdfUrl(developer, leafletForm)
        } else null

        val nearbyProgram = nearbyPrograms.first { p -> p.nid == programId }
        val latitude = nearbyProgram.lat
        val longitude = nearbyProgram.lng

        val program = Program(null, developer, programName, programId, postalCode, address, url, imgUrl, pdfUrl,
                latitude, longitude, mutableListOf(), null, null)
        programService.save(program)

        return program
    }

    private fun parseFormGetResultGetPdfUrl(developer: RealEstateDeveloper, result: FormGetResult): String {
        return when (developer) {
            RealEstateDeveloper.COGEDIM -> Jsoup.parse(result.form).select("div.confirmation a").attr("href")
            RealEstateDeveloper.KAUFMANBROAD -> Jsoup.parse(result.confirmation).select("div.download-btn v-btn").attr("href")
            else -> throw Exception("RealEstateDeveloper $developer not supported")
        }
    }

    private fun fetchSearchResult(developer: RealEstateDeveloper, page: Int, data: String): SearchResult? {
        val url = when {
            developer === RealEstateDeveloper.COGEDIM -> {
                "https://www.cogedim.com"
            }
            developer === RealEstateDeveloper.KAUFMANBROAD -> {
                "https://www.kaufmanbroad.fr"
            }
            else -> {
                throw Exception("Developer")
            }
        }

        return postRequest(
                developer,
                "$url/search-results?page=$page",
                data,
                SearchResult::class.javaObjectType
        )
    }

    private fun fetchFormBlueprint(developer: RealEstateDeveloper, programNumber: String, lotNumber: String): FormGetResult? {
        return when (developer) {
            RealEstateDeveloper.COGEDIM -> postRequest(
                    developer,
                    "${developer.baseurl}/form-get",
                    "form=re_forms_blueprint&program_nid=$programNumber&lot_id=$lotNumber&$contactinfoC",
                    FormGetResult::class.javaObjectType
            )
            RealEstateDeveloper.KAUFMANBROAD -> postRequest(
                    developer,
                    "${developer.baseurl}/form-post",
                    "form_id=re_forms_blueprint&program_nid=$programNumber&lot_id=$lotNumber&$contactinfoK",
                    FormGetResult::class.javaObjectType
            )
            else -> throw Exception("RealEstateDeveloper $developer not supported")
        }
    }

    private fun fetchFormLeaflet(developer: RealEstateDeveloper, programNumber: String): FormGetResult? {

        return when (developer) {
            RealEstateDeveloper.COGEDIM -> postRequest(
                    developer,
                    "${developer.baseurl}/form-get",
                    "form=re_forms_leaflet&program_nid=$programNumber&$contactinfoC",
                    FormGetResult::class.javaObjectType
            )
            RealEstateDeveloper.KAUFMANBROAD -> postRequest(
                    developer,
                    "${developer.baseurl}/form-post",
                    "form_id=re_forms_booklet&program_nid=$programNumber&$contactinfoK",
                    FormGetResult::class.javaObjectType
            )
            else -> throw Exception("RealEstateDeveloper $developer not supported")
        }
    }

    private fun <T> postRequest(developer: RealEstateDeveloper, url: String, writeData: String, gsonType: Class<T>): T? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            applyRequestHeaders(developer, this, true)
        }

        conn.outputStream.use { os ->
            OutputStreamWriter(os, "UTF-8").use { osw ->
                osw.write(writeData)
                osw.flush()
            }
        }

        return try {
            conn.inputStream.use {
                val result = IOUtils.toString(GZIPInputStream(it), StandardCharsets.UTF_8)
                try {
                    gson.fromJson(result, gsonType)
                } catch (e: java.lang.IllegalStateException) {
                    logger.error("Error converting response to json. " +
                            "Url = $url, writeData = $writeData, gsonType = ${gsonType.toGenericString()}",
                            e)
                    null
                }
            }
        } catch (e: java.io.IOException) {
            // if pdf not found it response 500, so skip the error 500
            if (conn.responseCode != 500) {
                logger.info("Request error. Url = $url, data = $writeData")
                val stream = if (conn.contentEncoding == "gzip") {
                    GZIPInputStream(conn.errorStream)
                } else {
                    conn.errorStream
                }
                logger.info(IOUtils.toString(stream, StandardCharsets.UTF_8))
            }
            null
        }
    }

    override fun flushPrograms() {
        logger.info("Flush...")
        val conn = URL("http://127.0.0.1:8080/internalFlushPrograms").openConnection()
        (conn as HttpURLConnection).requestMethod = "POST"
        logger.info(IOUtils.toString(conn.getInputStream(), StandardCharsets.UTF_8))
    }

    companion object {
        fun applyRequestHeaders(developer: RealEstateDeveloper, conn: URLConnection, isPost: Boolean): URLConnection = conn.apply {
            if (isPost) {
                (this as HttpURLConnection).requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            }
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Sec-Fetch-Site", "same-origin")
            setRequestProperty("Origin", developer.baseurl)
            setRequestProperty("Accept-Encoding", "gzip, deflate, br")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,fr;q=0.6,zh-TW;q=0.5")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.0.0; Pixel 2 XL Build/OPD1.170816.004) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/77.0.3865.120 Mobile Safari/537.36")
            setRequestProperty("Sec-Fetch-Mode", "cors")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Referer", developer.baseurl)
            setRequestProperty("Cookie", "BACKENDID=COGEDIM-WEB-01; _gcl_au=1.1.25026551.1571652669; _ga=GA1.2.952906528.1571652669; _gid=GA1.2.1470064266.1571652669; __sonar=198838560166151756; _fbp=fb.1.1571652669541.1487313776; gwcc=%7B%22fallback%22%3A%220970255255%22%2C%22clabel%22%3A%22gO6yCLyn9ooBEN_DucMD%22%2C%22backoff%22%3A86400%2C%22backoff_expires%22%3A1571739069%7D; CookieConsent={stamp:\\'QVcpBClEOrYbHJsETUMHoiUDAGjhCNRwq538sc/7iFVZZ0pFv/CWGw==\\'%2Cnecessary:true%2Cpreferences:true%2Cstatistics:true%2Cmarketing:true%2Cver:1%2Cutc:1571652681875}; _gat_UA-57280140-1=1")
            setRequestProperty("Connection", "keep-alive")
        }
    }
}
