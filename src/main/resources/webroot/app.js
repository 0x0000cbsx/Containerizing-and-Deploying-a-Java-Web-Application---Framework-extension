function show(url, targetId) {
    const target = document.getElementById(targetId);
    target.textContent = 'Loading...';
    fetch(url)
        .then(response => response.text().then(text => ({ ok: response.ok, status: response.status, text })))
        .then(({ ok, status, text }) => {
            target.textContent = ok ? text : 'Error ' + status + ': ' + text;
        })
        .catch(error => {
            target.textContent = 'Request failed: ' + error.message;
        });
}

function greet() {
    const name = document.getElementById('name').value;
    show('/hello?name=' + encodeURIComponent(name), 'result');
}

function loadPi() {
    show('/pi', 'pi-result');
}

function loadConfig() {
    show('/config', 'config-result');
}

document.getElementById('name').addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        greet();
    }
});
