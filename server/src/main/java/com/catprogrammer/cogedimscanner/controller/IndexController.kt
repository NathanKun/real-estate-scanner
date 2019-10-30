package com.catprogrammer.cogedimscanner.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class IndexController {

    @GetMapping("/")
    fun index() = "Cogedim Scanner Api"
}
