package com.xycz.simplelive.core.common

class CoreError(message: String, val statusCode: Int = 0, cause: Throwable? = null) :
    Exception(message, cause)
