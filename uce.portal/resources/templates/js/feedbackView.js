(() => {
    // Expand/collapse for feedback content headers (optional enhancement)
    document.querySelectorAll('.feedback-content h3').forEach(header => {
        header.style.cursor = 'pointer';
        header.addEventListener('click', () => {
            let next = header.nextElementSibling;
            while (next && next.tagName !== 'H3') {
                next.classList.toggle('collapsed');
                next = next.nextElementSibling;
            }
        });
    });
})();
