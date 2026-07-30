package io.legado.app.model.review

import java.util.ArrayDeque

/** 基于全部已加载回复纯函数重建稳定、无环的回复树。 */
object ParagraphReplyTreeBuilder {

    /** 按首次出现顺序去重，并以 reply_to_reply_id 重建跨页关系。 */
    fun build(pageReplies: Iterable<ParagraphReply>): List<ParagraphReply> {
        val nodes = LinkedHashMap<String, ParagraphReply>()
        pageReplies.forEach { root -> flattenInto(root, nodes) }
        if (nodes.isEmpty()) return emptyList()

        val childrenByParent = nodes.keys.associateWith { mutableListOf<String>() }.toMutableMap()
        val attached = hashSetOf<String>()
        nodes.forEach { (id, node) ->
            val parentId = node.replyToReplyId
            if (parentId != null && parentId != id && parentId in nodes &&
                !wouldCreateCycle(id, parentId, nodes)
            ) {
                childrenByParent.getValue(parentId).add(id)
                attached += id
            }
        }

        val roots = nodes.keys.filterNot(attached::contains)
        val built = HashMap<String, ParagraphReply>(nodes.size)
        roots.forEach { rootId -> buildRoot(rootId, nodes, childrenByParent, built) }
        return roots.mapNotNull(built::get)
    }

    /** 以非递归深度优先顺序收集服务端当前页的嵌套节点。 */
    private fun flattenInto(root: ParagraphReply, nodes: LinkedHashMap<String, ParagraphReply>) {
        val stack = ArrayDeque<ParagraphReply>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            nodes[node.replyId] = node.copy(children = emptyList())
            node.children.asReversed().forEach(stack::addLast)
        }
    }

    /** 检查候选父链是否会回到当前节点。 */
    private fun wouldCreateCycle(
        id: String,
        parentId: String,
        nodes: Map<String, ParagraphReply>,
    ): Boolean {
        val visited = hashSetOf<String>()
        var current: String? = parentId
        while (current != null && visited.add(current)) {
            if (current == id) return true
            current = nodes[current]?.replyToReplyId?.takeIf(nodes::containsKey)
        }
        return false
    }

    /** 以后序遍历自底向上创建不可变回复子树。 */
    private fun buildRoot(
        rootId: String,
        nodes: Map<String, ParagraphReply>,
        childrenByParent: Map<String, List<String>>,
        built: MutableMap<String, ParagraphReply>,
    ) {
        val stack = ArrayDeque<Pair<String, Boolean>>()
        stack.addLast(rootId to false)
        while (stack.isNotEmpty()) {
            val (id, visited) = stack.removeLast()
            if (visited) {
                val node = nodes.getValue(id)
                val children = childrenByParent[id].orEmpty().mapNotNull(built::get)
                built[id] = node.copy(children = children)
            } else {
                stack.addLast(id to true)
                childrenByParent[id].orEmpty().asReversed().forEach { childId ->
                    stack.addLast(childId to false)
                }
            }
        }
    }
}
