package com.bitboard.app

import android.app.Application
import android.content.Context
import com.bitboard.app.engine.BitBoardEngine

/** Holds the singleton engine instance for the process. */
class App : Application() {

    companion object {
        @Volatile
        private var instance: App? = null

        fun engine(context: Context): BitBoardEngine =
            (context.applicationContext as App).engine

        fun of(context: Context): App = context.applicationContext as App
    }

    lateinit var engine: BitBoardEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = BitBoardEngine(this)
    }
}
