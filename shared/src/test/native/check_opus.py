#!/usr/bin/env python3
"""Run with python3 shared/src/test/native/check_opus.py; needs a C compiler."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2] / 'main/jni/opus'
variables = {}
for name in ('opus', 'celt', 'silk'):
    text = (root / f'{name}_sources.mk').read_text().replace('\\\n', ' ')
    variables.update(re.findall(r'^(\w+)\s*=\s*(.*)$', text, re.M))
sources = [str(root / f) for key in (
    'OPUS_SOURCES', 'OPUS_SOURCES_FLOAT', 'CELT_SOURCES',
    'SILK_SOURCES', 'SILK_SOURCES_FLOAT',
) for f in variables[key].split()]
with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / 'check.c'
    source.write_text('''
#include <assert.h>
#include <math.h>
#include "opus.h"
int main(void) {
  const int rates[] = {16000, 24000, 48000};
  for (int r = 0; r < 3; r++) {
   for (int frame_ms = 20; frame_ms <= 40; frame_ms += 20) {
    int rate = rates[r], frame_samples = rate * frame_ms / 1000;
    int error;
    OpusEncoder *encoder = opus_encoder_create(rate, 1, OPUS_APPLICATION_VOIP, &error);
    assert(encoder && error == OPUS_OK);
    assert(opus_encoder_ctl(encoder, OPUS_SET_BITRATE(48000)) == OPUS_OK);
    OpusDecoder *decoder = opus_decoder_create(rate, 1, &error);
    assert(decoder && error == OPUS_OK);
    opus_int16 pcm[1920], decoded[1920]; unsigned char packet[1275];
    for (int i=0; i<frame_samples; i++) pcm[i] = (opus_int16)(10000*sin(i*0.0576));
    int count = opus_encode(encoder, pcm, frame_samples, packet, sizeof(packet));
    assert(count > 0);
    assert(opus_packet_get_nb_samples(packet, count, rate) == frame_samples);
    assert(opus_decode(decoder, packet, count, decoded, frame_samples, 0) == frame_samples);
    int peak = 0;
    for (int i=0; i<frame_samples; i++) if (abs(decoded[i]) > peak) peak = abs(decoded[i]);
    assert(peak > 1000);
    opus_encoder_destroy(encoder); opus_decoder_destroy(decoder);
   }
  }
}
'''.replace('#include <math.h>', '#include <math.h>\n#include <stdlib.h>'))
    executable = Path(directory) / 'check'
    subprocess.run(['cc', '-O2', '-DOPUS_BUILD', '-DUSE_ALLOCA', '-DHAVE_LRINT', '-DHAVE_LRINTF',
        *[f'-I{root / f}' for f in ('include', 'src', 'celt', 'silk', 'silk/float')],
        str(source), *sources, '-lm', '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
print('Opus 16/24/48 kHz mono: 20/40 ms encode/decode passed')
