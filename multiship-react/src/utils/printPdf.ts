/**
 * Open a PDF in the browser's print dialog without leaving the page: load it
 * into a hidden iframe and call print() once it has painted. Falls back to a
 * new tab where the print call is blocked. Shared by Orders and Bulk Mailer.
 */
export function printPdfBlob(blob: Blob) {
  const url = URL.createObjectURL(blob)
  const iframe = document.createElement('iframe')
  iframe.style.position = 'fixed'
  iframe.style.right = '0'
  iframe.style.bottom = '0'
  iframe.style.width = '0'
  iframe.style.height = '0'
  iframe.style.border = 'none'
  iframe.src = url
  iframe.onload = () => {
    // Small delay so the PDF viewer has a chance to paint before the printer picker opens.
    setTimeout(() => {
      try {
        iframe.contentWindow?.focus()
        iframe.contentWindow?.print()
      } catch {
        window.open(url, '_blank', 'noopener')
      }
      // Long enough for the printer picker's Print / Cancel to resolve.
      setTimeout(() => {
        try { document.body.removeChild(iframe) } catch { /* already removed */ }
        URL.revokeObjectURL(url)
      }, 60_000)
    }, 150)
  }
  document.body.appendChild(iframe)
}
