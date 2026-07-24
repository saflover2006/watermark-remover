#!/usr/bin/env bash
# AlmaLinux 10 Deployment Script for Watermark Remover Studio
# Usage: sudo bash deploy-almalinux.sh [domain_name]

set -e

DOMAIN="${1:-_}"
WEB_ROOT="/var/www/watermark-remover"

echo "=== Watermark Remover Studio - AlmaLinux 10 Installer ==="

# 1. Update system & install required packages
echo "[1/6] Installing dependencies (Nginx, Git, Node.js, Certbot)..."
dnf install -y epel-release || true
dnf install -y nginx git nodejs certbot python3-certbot-nginx firewalld

# 2. Build web bundle
echo "[2/6] Building static web bundle..."
if [ -f "package.json" ]; then
    npm install
    npm run build:web
else
    echo "Error: Run this script from the watermark-remover repository root."
    exit 1
fi

# 3. Deploy files to web root
echo "[3/6] Deploying web files to ${WEB_ROOT}..."
mkdir -p "${WEB_ROOT}"
cp -r www/* "${WEB_ROOT}/"
chown -R nginx:nginx "${WEB_ROOT}"
chmod -R 755 "${WEB_ROOT}"

# 4. Apply SELinux security context
echo "[4/6] Setting SELinux contexts..."
if command -v chcon &> /dev/null; then
    chcon -R -t httpd_sys_content_t "${WEB_ROOT}" || true
fi

# 5. Configure Nginx
echo "[5/6] Writing Nginx configuration..."
cat << 'EOF' > /etc/nginx/conf.d/watermark-remover.conf
server {
    listen 80;
    server_name DOMAIN_PLACEHOLDER;

    root /var/www/watermark-remover;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location ~* \.(css|js|png|jpg|jpeg|svg|webp|json)$ {
        expires 30d;
        add_header Cache-Control "public, no-transform";
    }

    gzip on;
    gzip_types text/plain text/css application/json application/javascript image/svg+xml;
}
EOF

sed -i "s/DOMAIN_PLACEHOLDER/${DOMAIN}/g" /etc/nginx/conf.d/watermark-remover.conf

# Test and restart Nginx
nginx -t
systemctl enable --now nginx

# 6. Configure Firewall
echo "[6/6] Setting up Firewall rules..."
systemctl enable --now firewalld || true
firewall-cmd --permanent --add-service=http
firewall-cmd --permanent --add-service=https
firewall-cmd --reload

echo "=========================================================="
echo " Deployment Complete!"
echo " Access your app in your browser at: http://${DOMAIN}"
if [[ "${DOMAIN}" != "_" && ! "${DOMAIN}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo " To enable HTTPS (SSL), run:"
    echo " certbot --nginx -d ${DOMAIN}"
else
    echo " Note: Let's Encrypt SSL (certbot) requires a domain name."
    echo " Your app is live over HTTP at http://${DOMAIN}"
fi
echo "=========================================================="
