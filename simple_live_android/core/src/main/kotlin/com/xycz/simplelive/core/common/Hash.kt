package com.xycz.simplelive.core.common

import java.security.MessageDigest

fun md5Hex(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

private val randomChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

fun randomString(length: Int, chars: String = randomChars): String =
    buildString { repeat(length) { append(chars[kotlin.random.Random.nextInt(chars.length)]) } }

fun randomDigits(length: Int): String =
    buildString {
        append(kotlin.random.Random.nextInt(1, 10))
        repeat(length - 1) { append(kotlin.random.Random.nextInt(10)) }
    }
