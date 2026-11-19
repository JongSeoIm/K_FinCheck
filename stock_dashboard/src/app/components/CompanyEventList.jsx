import { eventLabels } from '../lib/companyEvents'
import StockIcon from './StockIcon'

export default function CompanyEventList({ events }) {
  return <ul className='upcoming-events-list'>
    {events.map(event => <li key={event.id}>
      <time dateTime={event.date}>{event.date.slice(5).replace('-', '.')}</time>
      <StockIcon symbol={event.symbol} />
      <div><strong>{event.name}</strong><span>{eventLabels[event.type] || event.title}</span></div>
    </li>)}
  </ul>
}
