export const CHAT_NEAR_BOTTOM_THRESHOLD_PX = 80

export function isNearBottom(scrollContainer, threshold = CHAT_NEAR_BOTTOM_THRESHOLD_PX) {
  if (!scrollContainer) return true

  const distanceToBottom =
    scrollContainer.scrollHeight - scrollContainer.scrollTop - scrollContainer.clientHeight
  return distanceToBottom <= threshold
}
