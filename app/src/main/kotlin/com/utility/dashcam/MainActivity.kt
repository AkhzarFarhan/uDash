package com.utility.dashcam

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.utility.dashcam.service.DashcamIngestionService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Simple UI or direct service start for uDash
        setContentView(android.R.layout.simple_list_item_1)
        
        // Start the ingestion service
        val intent = Intent(this, DashcamIngestionService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
