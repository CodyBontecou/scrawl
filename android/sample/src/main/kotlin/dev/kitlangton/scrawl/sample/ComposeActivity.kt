package dev.kitlangton.scrawl.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.kitlangton.scrawl.Scrawl
import dev.kitlangton.scrawl.ScrawlScreen

/** A Compose screen, so touch labels come from Compose semantics. */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val view = LocalView.current
                remember { ScrawlScreen.attach(view, "Compose list") }
                var note by remember { mutableStateOf("") }
                Column(Modifier.padding(top = 48.dp, start = 16.dp, end = 16.dp)) {
                    Text("Compose", style = MaterialTheme.typography.headlineMedium)
                    TextField(note, { note = it }, label = { Text("Compose note") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        Button(onClick = { Scrawl.event("Compose save") }) { Text("Save draft") }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { Scrawl.event("Compose star") }) {
                            Icon(star, contentDescription = "Add to favorites")
                        }
                    }
                    LazyColumn {
                        items(30) { i ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Compose card ${i + 1}", style = MaterialTheme.typography.titleMedium)
                                    Text("Subtitle for card ${i + 1}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val star = ImageVector.Builder("star", 24.dp, 24.dp, 24f, 24f).path {
        moveTo(12f, 2f); lineTo(15f, 9f); lineTo(22f, 9f); lineTo(16.5f, 13.5f); lineTo(18.5f, 21f)
        lineTo(12f, 16.5f); lineTo(5.5f, 21f); lineTo(7.5f, 13.5f); lineTo(2f, 9f); lineTo(9f, 9f); close()
    }.build()
}
