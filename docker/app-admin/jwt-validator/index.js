import http from 'http';
import jwt from 'jsonwebtoken';
import jwksClient from 'jwks-rsa';

const TEAM_DOMAIN = process.env.CLOUDFLARE_TEAM_DOMAIN;
const AUD = process.env.CLOUDFLARE_AUD;
const PORT = process.env.PORT || 8080;

if (!TEAM_DOMAIN || !AUD) {
  console.error('Missing required environment variables: CLOUDFLARE_TEAM_DOMAIN, CLOUDFLARE_AUD');
  process.exit(1);
}

const client = jwksClient({
  jwksUri: `${TEAM_DOMAIN}/cdn-cgi/access/certs`,
  cache: true,
  cacheMaxAge: 86400000,
  rateLimit: true
});

function getKey(header, callback) {
  client.getSigningKey(header.kid, (err, key) => {
    if (err) {
      callback(err);
      return;
    }
    const signingKey = key.getPublicKey();
    callback(null, signingKey);
  });
}

async function validateJWT(token) {
  return new Promise((resolve, reject) => {
    jwt.verify(
      token,
      getKey,
      {
        audience: AUD,
        issuer: TEAM_DOMAIN,
        algorithms: ['RS256'],
        clockTolerance: 300
      },
      (err, decoded) => {
        if (err) {
          reject(err);
        } else {
          resolve(decoded);
        }
      }
    );
  });
}

const server = http.createServer(async (req, res) => {
  if (req.url !== '/validate' || req.method !== 'GET') {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
    return;
  }

  const token = req.headers['cf-access-jwt-assertion'];

  if (!token) {
    res.writeHead(401, { 'Content-Type': 'text/plain' });
    res.end('Unauthorized: Missing JWT token');
    return;
  }

  try {
    await validateJWT(token);
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('OK');
  } catch (error) {
    console.error('JWT validation failed:', error.message);
    res.writeHead(401, { 'Content-Type': 'text/plain' });
    res.end('Unauthorized: Invalid JWT token');
  }
});

server.listen(PORT, () => {
  console.log(`JWT validator listening on port ${PORT}`);
  console.log(`Team Domain: ${TEAM_DOMAIN}`);
  console.log(`Audience: ${AUD}`);
});
