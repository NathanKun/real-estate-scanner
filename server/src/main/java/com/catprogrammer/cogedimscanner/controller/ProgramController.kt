package com.catprogrammer.cogedimscanner.controller

import com.catprogrammer.cogedimscanner.entity.Program
import com.catprogrammer.cogedimscanner.model.ProgramDateLotDto
import com.catprogrammer.cogedimscanner.service.ProgramService
import com.catprogrammer.cogedimscanner.service.impl.CogedimCrawlerServiceImpl.Companion.applyRequestHeaders
import com.catprogrammer.cogedimscanner.utils.JwtTokenUtil
import com.fasterxml.jackson.annotation.JsonView
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import javax.servlet.http.HttpServletRequest


@RestController
@CacheConfig(cacheNames = ["programCache"])
@EnableScheduling
open class ProgramController {

    private val logger = LoggerFactory.getLogger(ProgramController::class.java)

    @Autowired
    private lateinit var programService: ProgramService

    @Autowired
    private lateinit var userDetailsService: UserDetailsService

    @Autowired
    private lateinit var jwtTokenUtil: JwtTokenUtil

    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @GetMapping("/programs")
    @JsonView(Program.SimpleView::class)
    open fun findAllGroupByProgramNumber(): List<ProgramDateLotDto> {
        return programService.findProgramsGroupByProgramNumber()
    }

    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @GetMapping("/program")
    @ResponseBody
    @Cacheable(key = "#url")
    open fun fetchProgramPageHtml(url: String): ResponseEntity<String> {
        return internalFetchProgramPageHtml(url)
    }

    @GetMapping("/resource")
    @Cacheable(key = "#resourceUrl")
    open fun fetchResource(resourceUrl: String?, token: String?, request: HttpServletRequest): ResponseEntity<ByteArray> {
        var auth = false

        if (token != null) {
            val username = jwtTokenUtil.getUsernameFromToken(token)
            if (username != null) {
                val userDetails = this.userDetailsService.loadUserByUsername(username)
                if (jwtTokenUtil.validateToken(token, userDetails)!!) {
                    val authentication = UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.authorities)
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(
                            request)
                    SecurityContextHolder.getContext().authentication = authentication
                }
            }

            if (SecurityContextHolder.getContext().authentication.authorities.toTypedArray().any {
                        it.authority == "WRITE_PRIVILEGE"
                    }) {
                auth = true
            }
        }

        return if (resourceUrl != null) {
            if ((resourceUrl.startsWith("https://www.cogedim.com/marker/") && auth) || // big map pin detail
                    (resourceUrl.startsWith("https://www.cogedim.com/sites/") && auth) || // program image
                    (resourceUrl.startsWith("https://www.cogedim.com/themes/") &&
                            (resourceUrl.endsWith(".png") ||
                                    resourceUrl.endsWith("jpg") ||
                                    resourceUrl.endsWith("jpeg"))) // images for css
            ) {
                internalFetchResource(resourceUrl)
            } else {
                ResponseEntity(HttpStatus.FORBIDDEN)
            }
        } else {
            ResponseEntity(HttpStatus.BAD_REQUEST)
        }
    }

    @CachePut(key = "#url")
    open fun internalFetchProgramPageHtml(url: String): ResponseEntity<String> {
        return if (url.startsWith("https://www.cogedim.com/")) {
            // avoid requesting cogedim's server concurrently
            synchronized(this) {
                Thread.sleep(2000)
                logger.info("requesting $url")
                val conn = applyRequestHeaders(URL(url).openConnection())
                val str = IOUtils.toString(GZIPInputStream(conn.getInputStream()), StandardCharsets.UTF_8)
                ResponseEntity
                        .ok()
                        .contentType(MediaType.parseMediaType(conn.contentType))
                        .body(str)
            }
        } else {
            ResponseEntity(HttpStatus.BAD_REQUEST)
        }
    }

    @CachePut(key = "#resourceUrl")
    open fun internalFetchResource(resourceUrl: String): ResponseEntity<ByteArray> {
        val conn = applyRequestHeaders(URL(resourceUrl).openConnection())
        val inputStream = if (conn.contentEncoding == "gzip") {
            GZIPInputStream(conn.getInputStream())
        } else {
            conn.getInputStream()
        }
        return ResponseEntity
                .ok()
                .contentType(MediaType.parseMediaType(conn.contentType))
                .body(IOUtils.toByteArray(inputStream))
    }

    /**
     * evict cache schedule
     */
    @CacheEvict(allEntries = true)
    @Scheduled(fixedDelay = (12 * 60 * 60 * 1000).toLong(), initialDelay = 1000)
    open fun reportCacheEvict() {
        logger.info("Flush Cache")
    }
}
