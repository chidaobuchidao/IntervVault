import { ref, computed } from 'vue'
import { authFetch } from '@/utils/authFetch'
import { isDocxFile, isPdfFile } from '@/utils/documentFile'

export interface PaperExportOptions {
  /** Paragraph data array (each element should have at least { index, text }) */
  paragraphData: () => any[]
  /** Map of polished/reduced paragraph text keyed by index */
  resultParagraphs: () => Map<number, string>
  /** The stored File object (for preserve-format export) */
  storedFile: () => File | null
  /** Download file name without extension (e.g. 'polished', 'ai-reduced') */
  downloadName: string
  /** Error setter -- receives error messages */
  setError: (msg: string) => void
  /** Warn setter -- receives warning messages (optional) */
  showWarn?: (msg: string) => void
}

/** Extract a human-readable message from an unknown thrown value. */
function toMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

export function usePaperExport(options: PaperExportOptions) {
  const { paragraphData, resultParagraphs, storedFile, downloadName, setError, showWarn } = options

  const showExport = ref(false)
  const isExporting = ref(false)

  const canPreserveFormat = computed(() => isDocxFile(storedFile()))
  const canUsePdfToWordExport = computed(() => isPdfFile(storedFile()))
  const needsOriginalFileForPreserve = computed(
    () => !storedFile() && paragraphData().length > 0
  )

  function triggerDownload(blob: Blob) {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${downloadName}.docx`
    a.click()
    URL.revokeObjectURL(url)
  }

  async function exportDoc(mode: 'preserve' | 'pdf-beta' | 'standard') {
    showExport.value = false

    if (mode === 'preserve' && !canPreserveFormat.value) {
      const msg = '原格式导出需要当前页面持有原始 DOCX 文件，请重新上传原 DOCX 后再导出。'
      setError(msg)
      showWarn?.(msg)
      return
    }

    if (mode === 'standard' && needsOriginalFileForPreserve.value) {
      const ok = confirm(
        '当前页面没有原始 DOCX 文件，标准 Word 导出不会保留原格式和图片。建议重新上传原 DOCX 后使用"原格式导出"。仍要继续标准导出吗？'
      )
      if (!ok) return
    }

    const allParas = paragraphData().map((p: any) => ({
      index: p.index,
      text: resultParagraphs().get(p.index) ?? p.text ?? '',
      originalText: p.text ?? '',
    }))
    const changedParas = allParas.filter(
      (p: any) => (resultParagraphs().get(p.index) ?? '').trim()
    )

    // PDF-to-Word beta export
    if (mode === 'pdf-beta' && storedFile() && canUsePdfToWordExport.value) {
      isExporting.value = true
      const fd = new FormData()
      fd.append('file', storedFile()!)
      fd.append('mappings', JSON.stringify({ fileName: downloadName, paragraphs: allParas }))
      try {
        const res = await authFetch('/api/paper-export/pdf-to-docx-preserve-format', {
          method: 'POST',
          body: fd,
        })
        if (res.ok) {
          triggerDownload(await res.blob())
          return
        }
        const errData = await res.json().catch(() => null)
        const msg = (errData?.detail || errData?.error || 'PDF 转 Word 保留导出失败') +
          '。标准导出不会保留原格式和图片，是否继续？'
        if (!confirm(msg)) { setError(''); return }
      } catch (err: unknown) {
        const msg = 'PDF 转 Word 导出失败: ' + toMessage(err) +
          '。标准导出不会保留原格式和图片，是否继续？'
        if (!confirm(msg)) { setError(''); return }
      } finally {
        isExporting.value = false
      }
    }

    // Preserve-format DOCX export
    if (mode === 'preserve' && storedFile() && canPreserveFormat.value) {
      const fd = new FormData()
      fd.append('file', storedFile()!)
      fd.append('mappings', JSON.stringify({ fileName: downloadName, paragraphs: changedParas }))
      try {
        const res = await authFetch('/api/paper-export/preserve-format', {
          method: 'POST',
          body: fd,
        })
        if (res.ok) {
          triggerDownload(await res.blob())
          return
        }
        const errData = await res.json().catch(() => null)
        const msg = (errData?.detail || errData?.error || '格式保留导出失败') +
          '。标准导出不会保留原格式和图片，是否继续？'
        if (!confirm(msg)) { setError(''); return }
      } catch (err: unknown) {
        const msg = '导出失败: ' + toMessage(err) +
          '。标准导出不会保留原格式和图片，是否继续？'
        if (!confirm(msg)) { setError(''); return }
      }
    }

    // Standard Word export (fallback or explicit)
    try {
      const res = await authFetch('/api/paper-export/standard', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileName: downloadName, paragraphs: allParas }),
      })
      if (res.ok) {
        triggerDownload(await res.blob())
        return
      }
      setError('标准导出也失败了，请重试')
    } catch (err: unknown) {
      setError('导出失败: ' + toMessage(err))
    }
  }

  return {
    showExport,
    isExporting,
    canPreserveFormat,
    canUsePdfToWordExport,
    needsOriginalFileForPreserve,
    exportDoc,
  }
}

export type UsePaperExportReturn = ReturnType<typeof usePaperExport>
