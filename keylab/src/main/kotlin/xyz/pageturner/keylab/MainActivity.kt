package xyz.pageturner.keylab

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

// Step-5 "hello" build: proves the build -> install -> logcat loop before KeyLab features.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "KeyLab: hello from the build loop"
            textSize = 28f
            setPadding(48, 96, 48, 48)
        })
        Log.i(TAG, "onCreate")
    }

    companion object {
        const val TAG = "KeyLab"
    }
}
