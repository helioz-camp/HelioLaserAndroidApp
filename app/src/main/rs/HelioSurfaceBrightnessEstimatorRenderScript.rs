#pragma version(1)
#pragma rs java_package_name(xyz.helioz.heliolaser)
#pragma rs_fp_relaxed

#pragma rs reduce(brightnessAccumulatorFunction) accumulator(brightnessAccumulator) combiner(brightnessCombiner) initializer(brightnessInitializer) outconverter(brightnessOutConvertor)

float targetHistogramProportion = 15.0/16.0;

struct Histogram {
    unsigned buckets[256];
};

static void brightnessInitializer(struct Histogram *accum) {
  for(unsigned n=0;sizeof(accum->buckets)/sizeof(accum->buckets[0]) > n; ++n) {
    accum->buckets[n] = 0;
  }
}

static void brightnessAccumulator(struct Histogram *accum, uchar4 in) {
  float val = 0.2126*in.r + 0.7152*in.g + 0.0722*in.b;
  uchar rounded = round(val);
  accum->buckets[rounded]++;
}

static void brightnessCombiner(struct Histogram*a, struct Histogram const*b) {
  for(unsigned n=0;sizeof(a->buckets)/sizeof(a->buckets[0]) > n; ++n) {
    a->buckets[n] += b->buckets[n];
  }
}

static void brightnessOutConvertor(float*out, const struct Histogram*accum) {
  unsigned total = 0;
  unsigned target;
  unsigned recount = 0;
  for(unsigned n=0;sizeof(accum->buckets)/sizeof(accum->buckets[0]) > n; ++n) {
    total += accum->buckets[n];
  }

  target = total*targetHistogramProportion;
  for(int i=sizeof(accum->buckets)/sizeof(accum->buckets[0])-1; i >= 0; --i) {
    recount += accum->buckets[i];
    if (recount >= target) {
        *out = i;
        return;
    }
  }
  *out = 0;
}