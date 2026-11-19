package pt.pem.tools

import javax.swing.text.AbstractDocument
import javax.swing.text.Element
import javax.swing.text.LabelView
import javax.swing.text.StyledEditorKit
import javax.swing.text.View
import javax.swing.text.ViewFactory

/** Vista de texto que deixa quebrar no meio de "palavras" enormes (XML/JSON numa linha, sem espacos). */
class WrapLabelView(e: Element) : LabelView(e) {
    override fun getMinimumSpan(axis: Int): Float = if (axis == View.X_AXIS) 0f else super.getMinimumSpan(axis)
}

/** StyledEditorKit cujo texto quebra em qualquer caractere quando a linha nao tem onde quebrar. */
class WrapStyledKit : StyledEditorKit() {
    private val base = super.getViewFactory()
    private val factory = ViewFactory { elem ->
        if (elem.name == AbstractDocument.ContentElementName) WrapLabelView(elem) else base.create(elem)
    }
    override fun getViewFactory(): ViewFactory = factory
}
