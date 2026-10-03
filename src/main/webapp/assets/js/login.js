(() => {
    'use strict';

    document.querySelectorAll('[data-password-toggle]').forEach((button) => {
        const input = document.getElementById(button.dataset.passwordToggle);
        if (!input) return;
        button.addEventListener('click', () => {
            const show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            button.textContent = show ? 'Ẩn' : 'Hiện';
            button.setAttribute('aria-pressed', String(show));
            input.focus();
        });
    });

    const form = document.getElementById('login-form');
    if (!form) return;
    const identity = document.getElementById('identity');
    const password = document.getElementById('password');
    const identityError = document.getElementById('identity-error');
    const passwordError = document.getElementById('password-error');
    const submitButton = document.getElementById('submit-button');
    addEventListener('pageshow', () => {
        form.classList.remove('is-loading');
        form.removeAttribute('aria-busy');
        submitButton.disabled = false;
    });

    const clearError = (input, error) => {
        input.setAttribute('aria-invalid', 'false');
        if (error) error.textContent = '';
    };
    identity.addEventListener('input', () => clearError(identity, identityError));
    password.addEventListener('input', () => clearError(password, passwordError));
    form.addEventListener('submit', (event) => {
        if (form.getAttribute('aria-busy') === 'true') {
            event.preventDefault();
            return;
        }
        let firstInvalid = null;
        if (!identity.value.trim()) {
            identity.setAttribute('aria-invalid', 'true');
            identityError.textContent = 'Vui lòng nhập tên đăng nhập hoặc email.';
            firstInvalid = identity;
        }
        if (!password.value) {
            password.setAttribute('aria-invalid', 'true');
            passwordError.textContent = 'Vui lòng nhập mật khẩu.';
            firstInvalid ??= password;
        }
        if (firstInvalid) {
            event.preventDefault();
            firstInvalid.focus();
            return;
        }
        form.classList.add('is-loading');
        form.setAttribute('aria-busy', 'true');
        submitButton.disabled = true;
    });
})();
