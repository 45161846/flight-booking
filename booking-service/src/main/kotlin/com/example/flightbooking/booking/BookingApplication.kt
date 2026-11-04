package com.example.flightbooking.booking

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class BookingApplication

fun main(args: Array<String>) { runApplication<BookingApplication>(*args) }
