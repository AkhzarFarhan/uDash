package com.ddpai.uploader.youtube

import java.io.IOException

class UploadHttpException(val code: Int, val bodyText: String) :
    IOException("upload HTTP $code: ${bodyText.take(300)}")
