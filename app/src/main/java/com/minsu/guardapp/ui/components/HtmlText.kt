package com.minsu.guardapp.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

/**
 * Renders a snippet of HTML — the shape announcement bodies arrive in from the office's rich-text
 * editor — as styled text.
 *
 * The office writes announcements in a WYSIWYG editor, so the body is HTML: `<p>`, `<strong>`,
 * `<em>`, `<a href>`, lists. Shown through a plain [Text] it read as raw markup — literal `<p>` tags
 * on screen. [AnnotatedString.fromHtml] (Compose 1.7+) turns the common inline and block tags into
 * spans a [Text] can draw, and links render interactively without any extra wiring.
 *
 * The parse is [remember]ed on the source so a scroll or recomposition does not re-parse every frame.
 */
@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated: AnnotatedString = remember(html, linkColor) {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = TextLinkStyles(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
        )
    }
    Text(text = annotated, modifier = modifier, style = style, color = color)
}
