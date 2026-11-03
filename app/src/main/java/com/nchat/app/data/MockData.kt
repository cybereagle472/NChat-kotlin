package com.nchat.app.data

import androidx.compose.ui.graphics.Color

data class Chat(
    val id: Int, val name: String, val message: String, val time: String,
    val unread: Int, val favorite: Boolean, val color: Color
)

object MockData {
    private val names = listOf("Areeb Khan", "Atif Hussain", "Ayesha Noor", "Hamza Ali", "Zain Ahmed",
        "Sara Malik", "Usman Tariq", "Hira Shah", "Bilal Khan", "Maham Iqbal",
        "Daniyal Raza", "Fatima Zahra", "Saad Javed", "Muneeb Aslam", "Iqra Bibi",
        "Abdullah", "Maryam", "Hassan Rauf", "Laiba", "Umar Farooq")
    private val messages = listOf("Assalam o alaikum! Kya haal hai?", "I will call you in a few minutes.",
        "The photos look amazing!", "Meeting ka time confirm kar do.", "Okay, see you tomorrow.",
        "Voice note sent", "Where are you right now?", "Thanks for the help!",
        "Let's plan something this weekend.", "Typing…", "Sample message for scrolling tests.",
        "File attached", "Good morning!", "Call me when you're free.")
    private val palette = listOf(Color(0xFF397C91), Color(0xFF8A5BA6), Color(0xFFB56C48),
        Color(0xFF4D8B69), Color(0xFFB05C78), Color(0xFF6478B5), Color(0xFF9B8246), Color(0xFF4D8F91))

    fun generate(size: Int): List<Chat> = List(size) { index ->
        val name = names[index % names.size] + if (index >= names.size) " ${index / names.size + 1}" else ""
        val minute = (index * 7) % 60
        Chat(index, name, messages[index % messages.size],
            "${index % 12 + 1}:${minute.toString().padStart(2, '0')} " + if (index % 2 == 0) "PM" else "AM",
            if (index % 4 == 0) index % 8 + 1 else 0, index % 7 == 0, palette[index % palette.size])
    }
}
