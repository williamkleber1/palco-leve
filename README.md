# Palco Leve

Instrumento Android minimalista para performance ao vivo com teclado MIDI e bancos SoundFont 2 (`.sf2`).

O objetivo é iniciar rápido, manter baixa latência e oferecer somente os controles necessários no palco. O projeto é original e não contém código, marca, interface ou conteúdo do Audio Evolution Mobile Studio.

## MVP

- motor nativo AAudio;
- sintetizador SF2 com TinySoundFont;
- teclado USB/Bluetooth MIDI pela API MIDI do Android;
- seletor de banco e preset;
- transpose, volume, sustain e pânico;
- diagnóstico de stream, latência e underruns;
- funcionamento offline, sem anúncios ou telemetria.

## Princípios técnicos

- uma SoundFont carregada por vez para limitar o uso de RAM;
- callback de áudio nativo sem alocações, bloqueios ou acesso a disco;
- fila MIDI sem bloqueio no lado do áudio;
- stream AAudio de baixa latência, com modo exclusivo e fallback compartilhado;
- suporte a Android 8.0 ou superior em `arm64-v8a` e `armeabi-v7a`.

## Compilar

O projeto abre normalmente no Android Studio. Para a compilação local mínima usada
durante o desenvolvimento, instale Android SDK 35, Build Tools 35.0.0, NDK
27.2.12479018 e JDK 17, e execute:

```sh
./build-manual.sh
```

O APK assinado para testes locais será criado em `dist/Palco-Leve-0.1.0.apk`.
A chave gerada é apenas de desenvolvimento e fica fora do Git.

Os resultados dos testes em hardware ficam em
[`docs/TESTED_DEVICES.md`](docs/TESTED_DEVICES.md).

## Dependência

[TinySoundFont](https://github.com/schellingb/TinySoundFont) é incluído como
submódulo Git e usado sob sua licença MIT. Bancos `.sf2` não fazem parte do app.
