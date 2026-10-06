package app.ljos.data

object Spots {
    val home = Spot("home", "Home · Njarðvík", 63.9740, -22.5490, dark = false)

    val all: List<Spot> = listOf(
        home,
        Spot("gardskagi", "Garðskagi lighthouse", 64.0817, -22.6897, dark = true),
        Spot("hafnir", "Hafnir", 63.9325, -22.6830, dark = true),
        Spot("reykjanesviti", "Reykjanesviti", 63.8160, -22.7040, dark = true),
        Spot("kleifarvatn", "Kleifarvatn", 63.9300, -21.9900, dark = true),
    )
}
