export async function readArtifactText(url: string, signal?: AbortSignal): Promise<string> {
    const response = await fetch(url, { signal, credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) throw new Error('Artifact preview is unavailable (' + response.status + ').');
    const reader = response.body?.getReader();
    if (!reader) return '';
    const decoder = new TextDecoder(); let text = ''; let bytes = 0;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.length;
        if (bytes > 16 * 1024 * 1024) throw new Error('Artifact preview exceeds the size limit.');
        text += decoder.decode(chunk.value, { stream: true });
      }
      return text + decoder.decode();
    } finally { await reader.cancel(); }
}
