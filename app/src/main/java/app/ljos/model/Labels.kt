package app.ljos.model

import app.ljos.L

/** The app's wording for a score band; the band itself comes from core. */
fun Chance.label(): String = when (this) {
    Chance.EXCELLENT -> L.t("Excellent", "Frábærar líkur")
    Chance.GOOD -> L.t("Good chance", "Góðar líkur")
    Chance.POSSIBLE -> L.t("Possible", "Mögulegt")
    Chance.UNLIKELY -> L.t("Unlikely", "Ólíklegt")
    Chance.NOT_TONIGHT -> L.t("Not tonight", "Ekki í kvöld")
}

/** Same call sites as before the core split: `Model.label(score)`. */
fun Model.label(score: Int): String = chance(score).label()
