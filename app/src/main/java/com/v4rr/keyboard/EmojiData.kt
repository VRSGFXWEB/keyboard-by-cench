package com.v4rr.keyboard

object EmojiData {
    val names = listOf(
        "FREQUENTLY USED", "SMILEYS & PEOPLE", "ANIMALS & NATURE", "FOOD & DRINK",
        "ACTIVITY", "TRAVEL & PLACES", "OBJECTS", "SYMBOLS", "FLAGS"
    )
    val icons = listOf("🕘", "😀", "🐻", "🍔", "⚽", "🚗", "💡", "🔣", "🏳️")

    private val smileys = "😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🫡 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 👋 🤚 ✋ 🖖 👌 🤌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 👍 👎 ✊ 👊 👏 🙌 🤝 🙏 💪 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💞 💓 💗 💖 💘 💝"
    private val animals = "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🕷️ 🦂 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦒 🐪 🐎 🐕 🐈 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🌺 🌻 🌹 🌷 🌼 🌸 💐 🌞 🌝 🌙 ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ ⛅ ☁️ ❄️ 💧 🌊"
    private val food = "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥕 🌽 🌶️ 🥔 🍠 🥐 🍞 🥖 🧀 🥚 🍳 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🍦 🍧 🍨 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍮 🍯 🍼 🥛 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹"
    private val activity = "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🎱 🏓 🏸 🥅 🏒 🏑 🥍 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 🛼 ⛸️ 🥌 🎿 ⛷️ 🏂 🏋️ 🤼 🤸 🤺 🏇 🧘 🏄 🏊 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩"
    private val travel = "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚨 🚔 🚍 🚘 🚖 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🚂 🚆 🚇 🚊 🚉 🏠 🏡 🏢 🏬 🏣 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🗼 🗽 🗻 🏔️ ⛰️ 🌋 🏕️ 🏖️ 🏝️ 🌅 🌄 🌃 🌉 🌌"
    private val objects = "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 📀 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ 🕰️ ⌛ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💴 💶 💷 💰 💳 💎 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧲 🔫 💣 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🔮 📿 💈 🔭 🔬 💊 💉 🧬 🌡️ 🧹 🧺 🧻 🚽 🚿 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🎁 🎈 🎉 🎊 ✉️ 📦 📝 📚 📎 ✂️ 🔒 🔓"
    private val symbols = "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ☮️ ✝️ ☪️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🔀 🔁 🔂 ▶️ ⏩ ◀️ ⏪ 🔼 🔽 ⏸️ ⏹️ ⏺️ 🎦 📶 📳 📴 ♻️ ✅ ☑️ ✔️ ✖️ ❌ ❎ ➕ ➖ ➗ ➰ ➿ 〽️ ✳️ ✴️ ❇️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ©️ ®️ ™️ #️⃣ *️⃣ 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 💯 🔞 🆗 🆒 🆕 🆓 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤"
    private val flags = "🏳️ 🏴 🏁 🚩 🏳️‍🌈 🇮🇩 🇺🇸 🇬🇧 🇯🇵 🇰🇷 🇨🇳 🇸🇬 🇲🇾 🇦🇺 🇧🇷 🇫🇷 🇩🇪 🇪🇸 🇮🇹 🇷🇺 🇹🇷 🇸🇦 🇮🇳 🇵🇭 🇹🇭 🇻🇳 🇳🇱 🇨🇦 🇲🇽 🇦🇷 🇵🇸 🇪🇬 🇿🇦 🇳🇬 🇵🇹 🇦🇪 🇨🇭 🇸🇪 🇳🇴 🇵🇱 🇺🇦 🇬🇷 🇮🇪 🇳🇿 🇨🇱 🇨🇴"

    /** index 1..8 (0 = recent, diisi dari Prefs) */
    val sets: List<List<String>> = listOf(smileys, animals, food, activity, travel, objects, symbols, flags)
        .map { it.split(" ").filter { s -> s.isNotEmpty() } }

    val words = listOf(
        "aku", "kamu", "dia", "kita", "kami", "mereka", "yang", "dan", "dengan", "untuk", "pada", "karena",
        "kalau", "tapi", "atau", "gimana", "kenapa", "apa", "siapa", "kapan", "dimana", "udah", "sudah",
        "belum", "nggak", "gak", "tidak", "iya", "oke", "makasih", "terima", "kasih", "maaf", "tolong",
        "halo", "hai", "selamat", "pagi", "siang", "sore", "malam", "cantik", "ganteng", "sayang", "rindu",
        "kangen", "cinta", "cepat", "besok", "kemarin", "sekarang", "nanti", "hari", "jangan", "harus",
        "boleh", "dulu", "banget", "juga", "lagi", "bisa", "mau", "ada", "ini", "itu",
        "the", "and", "you", "your", "that", "this", "with", "have", "what", "when", "where", "how", "why",
        "hello", "thanks", "please", "beautiful", "good", "morning", "night", "love", "miss", "okay", "yes",
        "sorry", "because", "about", "going", "would", "could", "just", "like", "for", "are", "was", "not", "but"
    )
}
