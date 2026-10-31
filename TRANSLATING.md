# Translating Sentrek

Sentrek is in English and Italian. Adding a language takes no code: copy the English texts into a new folder, translate them, and the language appears in Settings › Appearance › Language by itself, and in Android's own language setting for the app.

## 1. Make the folders

The texts are in five files, one per part of the app:

```
app/src/main/res/values/strings.xml
core/ui/src/main/res/values/strings.xml
feature/maps/src/main/res/values/strings.xml
feature/planner/src/main/res/values/strings.xml
feature/record/src/main/res/values/strings.xml
```

For each one, make a folder next to `values` named after your language, and copy `strings.xml` into it. For German, that's `values-de`:

```
app/src/main/res/values-de/strings.xml
```

The folder name is `values-` plus the [two-letter language code](https://en.wikipedia.org/wiki/List_of_ISO_639_language_codes). For a regional variant, add `-r` and the country code: `values-pt-rBR` for Brazilian Portuguese. For a script, use the long form: `values-b+sr+Latn` for Serbian in Latin script.

## 2. Translate

Change only the text between the tags, never the `name`:

```xml
<string name="record_save">Save track</string>      <!-- English -->
<string name="record_save">Track speichern</string>  <!-- German -->
```

A few things to keep as they are:

- **Placeholders** like `%s`, `%d`, `%1$s` and `%2$d` are filled in by the app (a distance, a time, a name). Keep every one of them; you can move them around in the sentence.
- **`%%`** is a percent sign. Write it as `%%`.
- **Apostrophes:** write `\'`, or use the typographic one `’`, which needs nothing.
- **Plurals** have one `<item>` per form. Android names the forms your language needs (`one`, `few`, `many`, `other`…); give each its own text. The [CLDR plural rules](https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html) list them.
- Lines starting with `<!--` are notes for translators. They don't need translating.
- `app_name` stays "Sentrek" and isn't in the translated files.

Some texts are short labels on buttons: try to keep them about as short as the English.

Texts sent by SMS (the ones starting with `breadcrumb_`) should use plain characters only, so each one fits in a single SMS.

## 3. Check it

From the project folder:

```sh
python scripts/check-translations.py de
```

It lists any text that's missing, any placeholder that doesn't match the English, and any text that's no longer used. When it says "All translations complete", build the app (see [BUILDING.md](BUILDING.md)) and choose your language in Settings › Appearance › Language to see it in place.

## 4. Send it

Open a pull request with your folders, or if you'd rather not use git, open an issue and attach the files.

When the English texts change later, the same script shows what's new to translate.
