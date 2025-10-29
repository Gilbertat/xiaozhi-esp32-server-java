const reportWebVitals = (onPerfEntry?: any) => {
  if (onPerfEntry && onPerfEntry instanceof Function) {
    import('web-vitals').then((webVitals) => {
      (webVitals as any).onCLS(onPerfEntry);
      (webVitals as any).onFID(onPerfEntry);
      (webVitals as any).onFCP(onPerfEntry);
      (webVitals as any).onLCP(onPerfEntry);
      (webVitals as any).onTTFB(onPerfEntry);
    });
  }
};

export default reportWebVitals;