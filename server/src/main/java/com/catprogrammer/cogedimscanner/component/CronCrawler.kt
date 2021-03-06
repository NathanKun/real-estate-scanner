package com.catprogrammer.cogedimscanner.component

import com.catprogrammer.cogedimscanner.service.CogedimCrawlerService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
open class CronCrawler {

    private val logger = LoggerFactory.getLogger(CronCrawler::class.java)

    @Autowired
    val cogedimCrawlerService: CogedimCrawlerService? = null

    @Scheduled(cron = "0 0 5 ? * *")
    fun cron() {
        logger.info("Crawler cron starts")
        val res = cogedimCrawlerService?.requestSearchResults()
        cogedimCrawlerService?.parseSearchResults(res!!, true)
        cogedimCrawlerService?.flushPrograms()
        logger.info("Crawler cron ends")
    }
}
