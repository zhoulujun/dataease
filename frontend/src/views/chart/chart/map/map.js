// import { hexColorToRGBA } from '@/views/chart/chart/util'
import { componentStyle } from '../common/common'
import { BASE_ECHARTS_SELECT, DEFAULT_TOOLTIP } from '@/views/chart/chart/chart'
import { isGradientValue } from '@/components/gradientColorSelector/base'
const linearCOlor = (start, end) => {
  return {
    type: 'linear',
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: start },
      { offset: 1, color: end }
    ],
    global: false
  }
}
const fillGradientColor = (data, colors) => {
  if (!data || !data.length) return data
  const colorLen = colors.length
  data.forEach((item, index) => {
    const colorIndex = index % colorLen
    const colorArr = colors[colorIndex]
    if (Array.isArray(colorArr)) {
      item.itemStyle = {
        normal: {
          areaColor: linearCOlor(colorArr[0], colorArr[1]),
          opacity: 0.6
        },
        emphasis: {
          areaColor: linearCOlor(colorArr[0], colorArr[1]),
          opacity: 1
        }
      }
    }
  })
  return data
}
export function baseMapOption(chart_option, chart, themeStyle, curAreaCode, seriesId) {
  // 处理shape attr
  let customAttr = {}
  let isGradient = false
  let seriesIndex = 0
  if (chart.customAttr) {
    customAttr = JSON.parse(chart.customAttr)
    if (chart.yaxis && chart.yaxis.length > 1) {
      let currentSeriesId = seriesId
      const yAxis = JSON.parse(chart.yaxis)
      if (!currentSeriesId || !yAxis.some(item => item.id === currentSeriesId)) {
        currentSeriesId = yAxis[0].id
      }
      chart.data.series.forEach((item, index) => {
        if (item.data[0].quotaList[0].id === currentSeriesId) {
          seriesIndex = index
          return false
        }
      })
    }

    if (customAttr.color) {
      const colorValue = customAttr.color.value
      isGradient = isGradientValue(colorValue)
      // chart_option.color = customAttr.color.colors
      if (customAttr.color.areaBorderColor) {
        chart_option.series[0].itemStyle.normal.borderColor = customAttr.color.areaBorderColor
      }
    }
    // tooltip
    if (customAttr.tooltip) {
      const tooltip = JSON.parse(JSON.stringify(customAttr.tooltip))
      const reg = new RegExp('\n', 'g')
      const text = tooltip.formatter.replace(reg, '<br/>')
      tooltip.formatter = params => {
        const a = params.seriesName
        const b = params.name
        const c = params.value ? params.value : ''
        return text.replace(new RegExp('{a}', 'g'), a).replace(new RegExp('{b}', 'g'), b).replace(new RegExp('{c}', 'g'), c)
      }
      chart_option.tooltip = tooltip

      const bgColor = tooltip.backgroundColor ? tooltip.backgroundColor : DEFAULT_TOOLTIP.backgroundColor
      chart_option.tooltip.backgroundColor = bgColor
      chart_option.tooltip.borderColor = bgColor
    }
  }
  // 处理data
  if (chart.data) {
    chart_option.title.text = chart.title
    if (chart.data.series && chart.data.series.length > 0) {
      chart_option.series[0].name = chart.data.series[seriesIndex].name
      chart_option.series[0].selectedMode = true
      chart_option.series[0].select = BASE_ECHARTS_SELECT
      // label
      if (customAttr.label) {
        const text = customAttr.label.formatter
        chart_option.series[0].label = customAttr.label
        chart_option.series[0].label.formatter = params => {
          const a = params.seriesName
          const b = params.name
          const c = params.value ? params.value : ''
          return text.replace(new RegExp('{a}', 'g'), a).replace(new RegExp('{b}', 'g'), b).replace(new RegExp('{c}', 'g'), c)
        }
        chart_option.series[0].labelLine = customAttr.label.labelLine
        if (customAttr.label.bgColor) {
          chart_option.series[0].label.backgroundColor = customAttr.label.bgColor
        }
        if (customAttr.label.showShadow) {
          chart_option.series[0].label.shadowBlur = 2
          chart_option.series[0].label.showdowColor = customAttr.label.shadowColor
        }
        chart_option.series[0].itemStyle.emphasis.label.show = customAttr.label.show
      }
      const valueArr = chart.data.series[seriesIndex].data
      // visualMap
      if (!isGradient) {
        if (valueArr && valueArr.length > 0) {
          const values = []
          valueArr.forEach(ele => {
            values.push(ele.value)
          })
          chart_option.visualMap.min = Math.min(...values)
          chart_option.visualMap.max = Math.max(...values)
          if (chart_option.visualMap.min === chart_option.visualMap.max) {
            chart_option.visualMap.min = 0
          }
        } else {
          chart_option.visualMap.min = 0
          chart_option.visualMap.max = 0
        }
        if (chart_option.visualMap.min === 0 && chart_option.visualMap.max === 0) {
          chart_option.visualMap.max = 100
        }
        // color
        if (customAttr.color && customAttr.color.colors) {
          chart_option.visualMap.inRange.color = customAttr.color.colors
          chart_option.visualMap.inRange.colorAlpha = customAttr.color.alpha / 100
        }
        if (themeStyle) {
          chart_option.visualMap.textStyle = { color: themeStyle }
        }
        if (customAttr.suspension && !customAttr.suspension.show) {
          chart_option.visualMap.show = false
        } else if ('show' in chart_option.visualMap) {
          delete chart_option.visualMap.show
        }
      }

      for (let i = 0; i < valueArr.length; i++) {
        const y = valueArr[i]
        y.name = chart.data.x[i]
        chart_option.series[0].data.push(y)
      }
      if (isGradient) {
        chart_option.series[0].data = fillGradientColor(chart_option.series[0].data, customAttr.color.colors)
        delete chart_option.visualMap
      }

      if (chart.senior) {
        const senior = JSON.parse(chart.senior)

        senior && senior.mapMapping && senior.mapMapping[curAreaCode] && (chart_option.series[0].nameMap = senior.mapMapping[curAreaCode])
      }
    }
  }
  componentStyle(chart_option, chart)
  return chart_option
}

