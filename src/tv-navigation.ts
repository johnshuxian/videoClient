const focusableSelector =
  "button:not(:disabled), input:not(:disabled), textarea:not(:disabled), a[href], [data-tv-focus]";

function visibleControls(scope: Element) {
  return Array.from(
    scope.querySelectorAll<HTMLElement>(focusableSelector),
  ).filter((element) => {
    const rect = element.getBoundingClientRect();
    const style = window.getComputedStyle(element);
    return (
      rect.width > 0 &&
      rect.height > 0 &&
      style.visibility !== "hidden" &&
      style.display !== "none" &&
      element.getAttribute("aria-hidden") !== "true"
    );
  });
}

export function focusTvElement(element: HTMLElement) {
  element.focus({ preventScroll: true });
  element.scrollIntoView({ block: "nearest", inline: "nearest" });
}

// WebView 的默认 Tab 顺序不对应海报网格和弹窗的四向布局，按屏幕位置选择下一焦点。
export function moveTvFocus(scope: Element, direction: string) {
  const controls = visibleControls(scope);
  if (!controls.length) return;
  const current = document.activeElement as HTMLElement;
  if (!controls.includes(current)) {
    focusTvElement(controls[0]);
    return;
  }

  const from = current.getBoundingClientRect();
  const x = from.left + from.width / 2;
  const y = from.top + from.height / 2;
  let next: HTMLElement | undefined;
  let best = Infinity;
  for (const element of controls) {
    if (element === current) continue;
    const rect = element.getBoundingClientRect();
    const dx = rect.left + rect.width / 2 - x;
    const dy = rect.top + rect.height / 2 - y;
    const main =
      direction === "ArrowRight"
        ? dx
        : direction === "ArrowLeft"
          ? -dx
          : direction === "ArrowDown"
            ? dy
            : -dy;
    if (main <= 4) continue;
    const cross =
      direction === "ArrowLeft" || direction === "ArrowRight"
        ? Math.abs(dy)
        : Math.abs(dx);
    const score = main + cross * 2.5;
    if (score < best) {
      best = score;
      next = element;
    }
  }
  if (next) focusTvElement(next);
}
