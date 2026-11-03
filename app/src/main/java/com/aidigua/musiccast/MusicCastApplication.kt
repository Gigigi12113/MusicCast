package com.aidigua.musiccast

import android.app.Application
import android.util.Log

class MusicCastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("MusicCast", "application onCreate")
    }
}
