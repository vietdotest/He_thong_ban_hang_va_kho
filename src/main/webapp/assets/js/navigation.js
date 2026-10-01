(() => {
    const menu = document.querySelector('.sidebar-navigation');
    if (!menu) return;
    const desktop = window.matchMedia('(min-width: 641px)');
    const adjust = () => { menu.open = desktop.matches; };
    adjust();
    desktop.addEventListener('change', adjust);
})();
