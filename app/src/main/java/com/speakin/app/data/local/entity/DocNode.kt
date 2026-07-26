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
 * content segment (text / image / audio) or a flow group where items arrange
 * themselves in a wrapping flow layout (like CSS flexbox wrap).
 *
 * JSON examples:
 *   Single segment:  {"type":"seg","content":{"text":"hello","spans":[]}}
 *   Flow group:      {"type":"flow","items":[{...}, {...}]}
 *   Column group (legacy): {"type":"cols","columns":[...]}
 */
@Serializable(with = DocNodeSerializer::class)
sealed class DocNode {

    /** A single content segment rendered at full width. */
    @Serializable
    @SerialName("seg")
    data class Segment(val content: RichSegment) : DocNode()

    /** A wrapping flow layout of items — each item takes its natural width
     *  and wraps to the next row when horizontal space runs out. */
    @Serializable
    @SerialName("flow")
    data class FlowGroup(val items: List<RichSegment>) : DocNode()

    /** Legacy: a horizontal row of 2-4 resizable columns.
     *  @deprecated Replaced by [FlowGroup].  Kept only for deserializing old data. */
    @Deprecated("Replaced by FlowGroup", ReplaceWith("FlowGroup"))
    @Serializable
    @SerialName("cols")
    data class ColumnGroup(val columns: List<ColumnData>) : DocNode()
}

/**
 * One column inside a [DocNode.ColumnGroup] (legacy format).
 *
 * @param weight proportional width relative to sibling columns (default 1f).
 * @param children the vertical list of content segments within this column.
 */
@Serializable
data class ColumnData(
    val weight: Float = 1f,
    val children: List<RichSegment> = emptyList()
)

// ── Migration helper ─────────────────────────────────────────────────

/** Convert legacy [DocNode.ColumnGroup] to the new [DocNode.FlowGroup] format. */
fun DocNode.ColumnGroup.toFlowGroup(): DocNode.FlowGroup =
    DocNode.FlowGroup(items = columns.flatMap { it.children })

// ── Polymorphic serializer ──────────────────────────────────────────

object DocNodeSerializer : JsonContentPolymorphicSerializer<DocNode>(DocNode::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out DocNode> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
        return when (type) {
            "flow" -> DocNode.FlowGroup.serializer()
            "cols" -> DocNode.ColumnGroup.serializer()  // legacy compat
            else   -> DocNode.Segment.serializer()       // "seg" or missing (fallback)
        }
    }
}

// ── Helper: flatten a DocNode list into a flat list of RichSegment ──

/**
 * Flatten a [DocNode] tree into a flat list of [RichSegment], recursing into
 * flow groups and legacy column groups.  Useful for text export, stats counting,
 * and media cleanup.
 */
fun List<DocNode>.flattenSegments(): List<RichSegment> = flatMap { node ->
    when (node) {
        is DocNode.Segment -> listOf(node.content)
        is DocNode.FlowGroup -> node.items
        is DocNode.ColumnGroup -> node.columns.flatMap { col -> col.children }
    }
}
