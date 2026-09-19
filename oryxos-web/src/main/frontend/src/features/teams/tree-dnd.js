/** HTML5 DnD helpers for Admin org/team tree reparent (same-kind only). */

export const ORG_DND_MIME = 'application/x-oryxos-org'
export const TEAM_DND_MIME = 'application/x-oryxos-team'

export function writeDragId(dataTransfer, mime, id) {
  if (!dataTransfer || !mime || !id) return
  dataTransfer.setData(mime, String(id))
  dataTransfer.effectAllowed = 'move'
}

export function readDragId(dataTransfer, mime) {
  if (!dataTransfer || !mime) return ''
  return String(dataTransfer.getData(mime) || '').trim()
}

/**
 * Resolve a same-kind tree drop into a set-parent call.
 * @param {{ draggedId: string, targetId?: string|null, mode: 'row'|'root' }}
 * @returns {{ skip: true } | { skip: false, parentId: string|null }}
 */
export function resolveTreeDrop({ draggedId, targetId, mode }) {
  const id = String(draggedId || '').trim()
  if (!id) return { skip: true }
  if (mode === 'root') {
    return { skip: false, parentId: null }
  }
  const parent = String(targetId || '').trim()
  if (!parent || parent === id) return { skip: true }
  return { skip: false, parentId: parent }
}

/**
 * Apply reparent via existing setParent API (cycle guard stays server-side → 400).
 * @returns {Promise<{ applied: false } | { applied: true, parentId: string|null }>}
 */
export async function applyTreeDrop({ draggedId, targetId, mode, setParent, reload }) {
  const resolved = resolveTreeDrop({ draggedId, targetId, mode })
  if (resolved.skip) return { applied: false }
  if (typeof setParent !== 'function') {
    throw new Error('setParent required')
  }
  await setParent(String(draggedId).trim(), resolved.parentId)
  if (typeof reload === 'function') await reload()
  return { applied: true, parentId: resolved.parentId }
}
