package com.chennan.moting

import android.app.Application
import com.chennan.moting.data.LibraryRepository

class MoTingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LibraryRepository.init(this)
    }
}
