package com.speakin.app.data.local.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A node in the rich-content document tree.
 *
 * The document is a flat list of DocNode items.  Each node is either a single
 * content segment (text / image / audio) or a row of side-by-side columns, where
 * each column holds its own vertical list of segments.
 *
 * JSON examples:
 *   Single segment:  {"type":"seg","content":{"text":"hello","spans":[]}}
 *   Column group:    {"type":"cols","columns":[{"weight":1.0,"children":[...]},...]}
 */
@Serializable(with = DocNodeSerializer::class)
sealed class DocNode {

    /** A single content segment rendered at full width. */
    @Serializable
    @SerialName("seg")
    data class Segment(val content: RichSegment) : DocNode()

    /** A horizontal row of 2-4 resizable columns. */
    @Serializable
    @SerialName("cols")
    data class ColumnGroup(val columns: List<ColumnData>) : DocNode()
}

/**
 * One column inside a [DocNode.ColumnGroup].
 *
 * @param weight proportional width relative to sibling columns (default 1f).
 *               A 2-column group with weights [1f, 2f] gives the second column
 *               twice the width of the first.
 * @param children the vertical list of content segments within this column.
 */
@Serializable
data class ColumnData(
    val weight: Float = 1f,
    val children: List<RichSegment> = emptyList()
)

// ── Polymorphic serializer ──────────────────────────────────────────

object DocNodeSerializer : JsonContentPolymorphicSerializer<DocNode>(DocNode::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out DocNode> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
        return when (type) {
            "cols" -> DocNode.ColumnGroup.serializer()
            else   -> DocNode.Segment.serializer()   // "seg" or missing (fallback)
        }
    }
}

// ── Helper: flatten a DocNode list into a flat list of RichSegment ──

/**
 * Flatten a [DocNode] tree into a flat list of [RichSegment], recursing into
 * column groups.  Useful for text export, stats counting, and media cleanup.
 */
fun List<DocNode>.flattenSegments(): List<RichSegment> = flatMap { node ->
    when (node) {
        is DocNode.Segment -> listOf(node.content)
        is DocNode.ColumnGroup -> node.columns.flatMap { col -> col.children }
    }
}
