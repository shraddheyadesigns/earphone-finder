package com.shady.earphonefinder.ui.glow

/**
 * The edge glow, ported 1:1 from the web prototype (tools/glow-prototype.html).
 * Verified by translating back to GLSL and diffing pixels against the original: identical.
 * One change from the prototype: arc shape is driven by uFound, colour by uWarm, so the
 * temperature colours show while still calibrating.
 * Coordinates are flipped to y-up at the top of main() so all the maths matches the prototype.
 */
internal const val GLOW_AGSL = """
uniform float2 uRes;
uniform float uTime;
uniform float uDir;
uniform float uHot;
uniform float uWarm;
uniform float uFound;
uniform float uWidth;
uniform float uBright;
uniform float uShimmer;
uniform float uRadius;
uniform float uFlow;
uniform float uPix;
uniform float uTwinkle;
uniform float uDither;
uniform float uGap;
uniform float uGlowMix;
uniform float uSpread;
uniform float uSoft;
uniform float uAmbient;
uniform float uSpinFocus;

float hash(float2 p){ return fract(sin(dot(p, float2(127.1,311.7))) * 43758.5453); }
float noise(float2 p){
  float2 i = floor(p), f = fract(p); f = f*f*(3.0-2.0*f);
  return mix(mix(hash(i), hash(i+float2(1.0,0.0)), f.x), mix(hash(i+float2(0.0,1.0)), hash(i+float2(1.0,1.0)), f.x), f.y);
}
float fbm(float2 p){ float v = 0.0, a = 0.5; for(int i=0;i<4;i++){ v += a*noise(p); p *= 2.03; a *= 0.5; } return v; }
float sdRoundBox(float2 p, float2 b, float r){ float2 q = abs(p) - b + r; return length(max(q,0.0)) + min(max(q.x,q.y),0.0) - r; }
float bayer2(float2 a){ a = floor(a); return fract(a.x/2.0 + a.y*a.y*0.75); }
float bayer4(float2 a){ return bayer2(0.5*a)*0.25 + bayer2(a); }

// closeness c: 0 = legit far (faint blue), 0.37 = ~10 ft (icy blue), 0.48 = ~7 ft (pale steel),
// 0.6 = ~5 ft (warm peach-ember), 0.8 = orange, 1 = ~1 ft (yellow at the edge).
// Brightness only ever goes UP as you close in: dimming would read as "colder".
float3 temp(float c){
  float3 k0 = float3(0.12,0.22,0.38);
  float3 k1 = float3(0.32,0.68,1.0);
  float3 k2 = float3(0.62,0.86,1.0);
  float3 k3 = float3(1.0,0.56,0.26);
  float3 k4 = float3(1.0,0.36,0.07);
  float3 k5 = float3(1.0,0.64,0.22);
  if (c < 0.37) return mix(k0, k1, smoothstep(0.0, 0.37, c));
  if (c < 0.48) return mix(k1, k2, smoothstep(0.37, 0.48, c));
  if (c < 0.60) return mix(k2, k3, smoothstep(0.48, 0.60, c));
  if (c < 0.80) return mix(k3, k4, smoothstep(0.60, 0.80, c));
  return mix(k4, k5, smoothstep(0.80, 1.0, c));
}
float lumAt(float c){ return c < 0.37 ? mix(0.45, 1.0, smoothstep(0.0, 0.37, c)) : mix(1.0, 1.2, smoothstep(0.37, 1.0, c)); }

float dirWeight(float ang0){
  float cd = cos(ang0 - uDir);
  float loaderW = exp(uSpinFocus * (cd - 1.0));
  float ang = acos(clamp(cd, -1.0, 1.0));
  float arcW = (1.0 - smoothstep(uSpread, uSpread + uSoft, ang)) * (0.35 + 0.3*exp(1.5*(cd - 1.0)));
  return mix(loaderW, arcW, uFound);   // shape waits for a direction; colour (uWarm) doesn't
}

float3 glowAt(float2 px){
  float2 h = uRes*0.5;
  float2 q = px - h;                            // y up
  float m = min(uRes.x, uRes.y);
  float e = max(-sdRoundBox(q, h, max(uRadius, 0.42*m)), 0.0);   // px in from the edge, extra round so it never looks like a frame
  // the glow grows deeper as you close in
  // up close the edge glow melts away into the screen wash
  float near = smoothstep(0.72, 1.0, uHot) * uWarm;
  float w = uWidth * m * (1.0 + 0.35*uHot*uWarm) * (1.0 - 0.45*near);

  // direction comes from the nearest point on the edge, so light flows straight in
  // instead of converging on the centre
  float2 n = q / h;
  float dxs = (1.0 - abs(n.x)) * h.x, dys = (1.0 - abs(n.y)) * h.y;
  float2 bx = float2(sign(n.x), n.y), by = float2(n.x, sign(n.y));
  float2 b = mix(bx, by, smoothstep(-0.16*m, 0.16*m, dxs - dys));
  float a = atan(b.x, b.y);

  float t = uTime * uFlow * (0.6 + 0.9*uHot*uWarm);
  float f = fbm(float2(a*2.6 + t*0.15, e/w*1.4 - t*0.9));
  float f2 = fbm(float2(a*7.0 - t*0.2, e/w*3.0 - t*1.6));
  float flame = mix(1.0, 0.35 + 1.25*f + 0.35*f2, uShimmer);

  // a rounder depth for the long soft tail, so the inside never shows a box shape
  float2 an = abs(q / h);
  float rr = pow(pow(an.x, 3.0) + pow(an.y, 3.0), 1.0/3.0);
  float eS = (1.0 - rr) * 0.5 * m;
  // core hugs the edge (edge-based angle); the long tail uses a seamless centre-based angle
  float aC = atan(q.x / h.x, q.y / h.y);
  float g = 0.62*exp(-e/w)*dirWeight(a) + 0.38*exp(-eS/(w*3.2))*dirWeight(aC);
  g *= flame;
  float clear = smoothstep(0.04*m, 0.46*m, eS);
  g *= 1.0 - clear*clear*(3.0 - 2.0*clear);   // fade very gradually toward a clear middle
  g *= 1.0 - 0.35*near;



  float3 slate = float3(0.52,0.60,0.72);
  float3 tc = temp(uHot);
  float3 hotCore = tc + float3(0.2,0.2,0.04) * smoothstep(0.75,1.0,uHot) * exp(-e/(w*(0.5 + 1.4*smoothstep(0.75,1.0,uHot))));
  float3 hotTail = tc * float3(1.0,0.7,0.6);
  float3 warmCol = mix(hotTail, hotCore, exp(-e/(w*0.9)));
  float3 col = mix(slate * (1.0 - 0.35*(1.0-exp(-e/w))), warmCol, uWarm);
  float lum = mix(0.8, lumAt(uHot), uWarm);

  float3 c = col * g * uBright * lum;
  return 1.0 - exp(-c * 1.25);                // soft tone map
}

// whole-screen wash: strongest when very hot or very cold, leaning toward the bud's side
float3 ambientAt(float2 px){
  float2 q = (px - uRes*0.5) / (uRes*0.5);
  float aPix = atan(q.x, q.y);
  float near = smoothstep(0.72, 1.0, uHot) * uWarm;
  float lean = mix(pow(0.5 + 0.5*cos(aPix - uDir), 1.5), 1.0, near);
  float heat = 0.35*smoothstep(0.1, 0.45, uHot) + 0.65*smoothstep(0.5, 0.95, uHot);   // wash only ever builds as you close in
  float3 tc = temp(uHot) * mix(float3(1.0), float3(0.75,0.42,0.28), smoothstep(0.55, 0.85, uHot)); // rusty, not neon
  float lvl = uAmbient * uWarm * (0.06 + 0.5*heat + 0.25*near) * (0.35 + 0.65*lean) * uBright;
  return tc * lvl;
}

half4 main(float2 fragCoord) {
  float2 px = float2(fragCoord.x, uRes.y - fragCoord.y);   // flip to y-up like the prototype
  float3 smoothGlow = glowAt(px) + (hash(px + fract(uTime)) - 0.5) / 128.0;
  float3 c;
  if (uPix > 0.5) {
    float2 cell = floor(px / uPix);
    float2 local = fract(px / uPix) - 0.5;
    float2 cc = (cell + 0.5) * uPix;
    float3 d = glowAt(cc);
    // from ~7 ft in, sparse dots start creeping toward the centre, more at 5 ft, most at 1 ft
    float inDots = pow(clamp((uHot - 0.44) / 0.53, 0.0, 1.0), 0.75) * uWarm;
    if (inDots > 0.0) {
      float patchV = smoothstep(0.40, 0.66, fbm(cc / min(uRes.x, uRes.y) * 5.0 + float2(uTime*0.05, -uTime*0.08)));
      // only every other cell, both ways: wider spacing than the edge dots
      float sparse = (1.0 - step(0.5, mod(cell.x, 2.0))) * (1.0 - step(0.5, mod(cell.y, 2.0)));
      // keep the area behind the number and subtext free of creeping dots
      float2 tq = (cc - float2(0.5, 0.47) * uRes) / (float2(0.40, 0.11) * uRes);
      sparse *= smoothstep(0.8, 1.5, length(tq));
      float3 tint = max(ambientAt(cc), temp(uHot) * 0.12);
      d += tint * 6.0 * inDots * patchV * sparse;
    }
    // each dot breathes on its own clock, faster when hot
    float h1 = hash(cell), h2 = hash(cell + 17.3);
    float speed = (1.5 + 3.0*h2) * (0.6 + 1.2*uHot*uWarm);
    float tw = 0.5 + 0.5*sin(uTime*speed + h1*6.2832);
    float spark = step(0.992, hash(cell + floor(uTime*6.0 + h1*10.0)));
    d *= mix(1.0, 0.35 + 0.9*tw, uTwinkle) + spark*uTwinkle*1.4*length(d);
    // ordered dither into a few brightness steps
    float L = max(max(d.r, d.g), d.b);
    if (L > 0.0) {
      float lv = 5.0;
      float qv = floor(L*lv + bayer4(cell) * uDither + (1.0-uDither)*0.5) / lv;
      d *= qv / L;
    }
    // round dots with breathing room around them
    float r = 0.5 * (1.0 - uGap);
    float aa = 1.0 / uPix;
    float dotMask = 1.0 - smoothstep(r - aa, r + aa, length(local));
    d *= dotMask * 1.6;
    // smooth glow underneath, dots on top
    c = smoothGlow * uGlowMix + d;
  } else {
    c = smoothGlow;
  }
  c += ambientAt(px);
  return half4(half3(c + float3(0.012,0.012,0.016)), 1.0);
}
"""
