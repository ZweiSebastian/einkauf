# Einkauf – Monatsliste → Wocheneinkäufe (Android, Galaxy S23 Ultra)

Tab **Monat**: einfach runterschreiben, was im Monat gebraucht wird – eine Zeile pro Artikel.
Die App verteilt alles automatisch auf **W1–W4** (oder 5 Wochen), dort wird abgehakt.

## Monatsliste
Jede Zeile hat eigene Felder: **Menge | Artikel | da | Woche**.

| Eingabe | Ergebnis (4 Wochen) |
|---|---|
| 60 · Bananen | 15 / 15 / 15 / 15 |
| 60 · Bananen · da 4 | 11 / 15 / 15 / 15 |
| 1 · Müllbeutel | in die Woche mit den wenigsten Artikeln |
| 6 · Eier · Woche W3 | alle 6 in Woche 3 |
| 6 · Käse · Woche W3+4 | 3 / 3 nur in Woche 3 und 4 |
| 2 · Waschmittel · Woche W1+3 | alle zwei Wochen: 1 in W1, 1 in W3 |

„Woche“ antippen öffnet eine Auswahl zum Ankreuzen (beliebige Kombination, „Alle“ = automatisch). Artikel leeren = Zeile löschen.

## Wochenlisten
**Tippen** = abhaken. **Lange drücken** öffnet:
- *Nur teilweise gekauft …*: z. B. 4 von 6 bekommen, dann bleiben 2 in dieser Woche offen
- *Schon zuhause vorhanden …*
- *Wochen wählen …* / *Auf alle Wochen verteilen*

Komplett vorrätige Artikel stehen abgehakt mit „alles schon da“ in der Liste.
Unten in jeder Woche gibt es ein Feld für Nachträge nur für diese Woche („3 Avocados“).
Alles wird nur lokal auf dem Handy gespeichert.

## APK bauen (nur Browser nötig)
1. Neues Repository auf github.com anlegen.
2. Diesen Ordner hochladen (inkl. des versteckten Ordners `.github`).
3. Unter **Actions** läuft „APK bauen“ automatisch (~5 Min.).
4. Unter **Releases** liegt danach `Einkauf.apk` → auf dem Handy herunterladen und installieren.
