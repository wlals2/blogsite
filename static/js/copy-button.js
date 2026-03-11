// ========================================
// Code Block Copy Button Functionality
// ========================================

document.addEventListener('DOMContentLoaded', function() {
    addCopyButtonsToCodeBlocks();
});

/**
 * Add copy buttons to all code blocks
 */
function addCopyButtonsToCodeBlocks() {
    // Find all code blocks with highlight class
    const codeBlocks = document.querySelectorAll('div.highlight');

    codeBlocks.forEach((block, index) => {
        // Skip if button already exists
        if (block.querySelector('.copy-button-wrapper')) {
            return;
        }

        // Create wrapper
        const wrapper = document.createElement('div');
        wrapper.className = 'copy-button-wrapper';

        // Create button
        const button = document.createElement('button');
        button.className = 'copy-code-button';
        button.type = 'button';
        button.setAttribute('aria-label', 'Copy code');
        button.innerHTML = `
            <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path d="M8 4v12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V4m0 0H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z"/>
            </svg>
        `;

        // Create tooltip
        const tooltip = document.createElement('div');
        tooltip.className = 'copy-tooltip';
        tooltip.textContent = 'Copied!';

        wrapper.appendChild(button);
        wrapper.appendChild(tooltip);
        block.appendChild(wrapper);

        // Add click event
        button.addEventListener('click', () => copyCode(button, block, tooltip));
    });
}

/**
 * Copy code to clipboard
 * @param {HTMLElement} button - The copy button element
 * @param {HTMLElement} block - The code block element
 * @param {HTMLElement} tooltip - The tooltip element
 */
function copyCode(button, block, tooltip) {
    // Extract text from code block
    const preElement = block.querySelector('pre');
    if (!preElement) return;

    let code = preElement.textContent;

    // Remove line numbers if present (for numbered code blocks)
    code = code.replace(/^\d+\s+/gm, '');

    // Copy to clipboard
    if (navigator.clipboard) {
        navigator.clipboard.writeText(code).then(() => {
            showCopySuccess(button, tooltip);
        }).catch(() => {
            fallbackCopyToClipboard(code, button, tooltip);
        });
    } else {
        fallbackCopyToClipboard(code, button, tooltip);
    }
}

/**
 * Fallback copy to clipboard for older browsers
 * @param {string} text - Text to copy
 * @param {HTMLElement} button - The copy button element
 * @param {HTMLElement} tooltip - The tooltip element
 */
function fallbackCopyToClipboard(text, button, tooltip) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();

    try {
        document.execCommand('copy');
        showCopySuccess(button, tooltip);
    } catch (err) {
        console.error('Failed to copy:', err);
    } finally {
        document.body.removeChild(textarea);
    }
}

/**
 * Show copy success state
 * @param {HTMLElement} button - The copy button element
 * @param {HTMLElement} tooltip - The tooltip element
 */
function showCopySuccess(button, tooltip) {
    // Add copied state
    button.classList.add('copied');
    tooltip.classList.add('show');

    // Change icon to checkmark
    const originalHTML = button.innerHTML;
    button.innerHTML = `
        <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M20 6L9 17l-5-5"/>
        </svg>
    `;

    // Reset after 2 seconds
    setTimeout(() => {
        button.classList.remove('copied');
        tooltip.classList.remove('show');
        button.innerHTML = originalHTML;
    }, 2000);
}

/**
 * Handle dynamic content (e.g., when new code blocks are added via JavaScript)
 */
function observeNewCodeBlocks() {
    const observer = new MutationObserver(() => {
        addCopyButtonsToCodeBlocks();
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true
    });
}

// Start observing for dynamic content
observeNewCodeBlocks();
